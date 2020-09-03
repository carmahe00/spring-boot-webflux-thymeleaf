package com.springboot.webflux.app.controllers;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.thymeleaf.spring5.context.webflux.ReactiveDataDriverContextVariable;

import com.springboot.webflux.app.models.documents.Categoria;
import com.springboot.webflux.app.models.documents.Producto;
import com.springboot.webflux.app.models.services.ProductoService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 
 * @author juan
 *
 *         Nota: No se usa .subscribe(), proque en la vista se subcibe
 */
@SessionAttributes("producto")
@Controller
public class ProductoController {

	@Autowired
	private ProductoService service;

	private static final Logger log = LoggerFactory.getLogger(ProductoController.class);
	
	/**
	 * @param path obtien el valor de application.properties
	 */
	@Value("${config.uploads.path}")
	private String path;
	
	/**
	 * método para agregar atributo  guarda en la vista como Model
	 * 
	 * @return todas las categorías
	 */
	@ModelAttribute("categorias")
	public Flux<Categoria> categorias(){
		return service.findAllCategoria();
	}

	@GetMapping("/uploads/img/{nombreFoto:.+}")
	public Mono<ResponseEntity<Resource>> verFoto(@PathVariable String nombreFoto) throws MalformedURLException{
		//ruta de la imágen
		Path ruta = Paths.get(path).resolve(nombreFoto).toAbsolutePath();
		Resource imagen = new UrlResource(ruta.toUri());
		return Mono.just(
				ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""+imagen.getFilename()+"\"")
				.body(imagen)
		);
	}
	
	@GetMapping("/ver/{id}")
	public Mono<String> ver(Model model, @PathVariable String id){
		return service.findById(id)
				.doOnNext(p ->{
					model.addAttribute("producto", p);
					model.addAttribute("titulo", "Detalle Producto");
				}).switchIfEmpty(Mono.just(new Producto()))
				.flatMap(p->{
					if (p.getId() == null) {
						return Mono.error(new InterruptedException("No existe el producto"));
					}
					return Mono.just(p);
				}).thenReturn("ver")
				.onErrorResume(ex -> Mono.just("redirect:/listar?error=no+existe+el+producto"));
	}
	
	@GetMapping({ "/listar", "/" })
	public Mono<String> listar(Model model) {
		Flux<Producto> productos = service.findAllConNombreUpperCase();

		productos.subscribe(prod -> log.info(prod.getNombre()));

		model.addAttribute("titulo", "Listado De Productos");
		model.addAttribute("productos", productos);
		return Mono.just("listar");
	}

	/**
	 * método para retornar la vista
	 * 
	 * @return Mono de string que contiene la vista
	 */
	@GetMapping("/form")
	public Mono<String> crear(Model model) {
		model.addAttribute("producto", new Producto());
		model.addAttribute("titulo", "Formulario de Producto");
		model.addAttribute("boton", "Crear");
		return Mono.just("form");
	}

	/**
	 * 
	 * @param id    identificador del cliente
	 * @param model envía datos a la vista dentro del flujo. Nota: necesita que se
	 *              envíe el id desde la vista, poruqe no se ejecuta todo dentro del
	 *              flujo y no maneja sesión .flatMap() convierte el objeto en un
	 *              error de flujo .onErrorResume() respaldo cuando ocurra un error
	 * @return la vista | redirecciona a listar con mensaje de error
	 */
	@GetMapping("/form-v2/{id}")
	public Mono<String> editarV2(@PathVariable String id, Model model) {
		return service.findById(id).doOnNext(p -> {
			log.info("Producto: " + p.getNombre());
			model.addAttribute("boton", "Editar");
			model.addAttribute("titulo", "Editar Producto");
			model.addAttribute("producto", p);
		}).defaultIfEmpty(new Producto()).flatMap(p -> {
			if (p.getId() == null) {
				return Mono.error(new InterruptedException("No existe el producto"));
			}
			return Mono.just(p);
		}).thenReturn("form").onErrorResume(ex -> Mono.just("redirect:/listar?error=no+existe+el+producto"));

	}

	/**
	 * método para obtener el producto. .defaultIfEmpty() si es vacio pasamos una
	 * nueva instancia
	 * 
	 * @param id    identificador producto
	 * @param model pasa atributos a la vista
	 * @return la página de crear
	 */
	@GetMapping("/form/{id}")
	public Mono<String> editar(@PathVariable String id, Model model) {
		Mono<Producto> productoMono = service.findById(id).doOnNext(p -> {
			log.info("Producto: " + p.getNombre());
		}).defaultIfEmpty(new Producto());
		model.addAttribute("titulo", "Editar Producto");
		model.addAttribute("producto", productoMono);
		model.addAttribute("boton", "Editar");
		return Mono.just("form");
	}

	/**
	 * método para guardar producto. .thenReturn() función cuando termina el flujo
	 * 
	 * @Valid valida igual que @ModelAttribute, pero la difrencia lo obtiene del
	 *        Nombre de la clase
	 * @param producto producto ha guardar
	 * @return Mono redirecciona a listar
	 */
	@PostMapping("/form")
	public Mono<String> guardar(@Valid Producto producto, BindingResult result, SessionStatus status, Model model, @RequestPart FilePart file) {
		if (result.hasErrors()) {
			model.addAttribute("titulo", "Error en el formulario producto");
			model.addAttribute("boton", "Guardar");
			return Mono.just("form");
		} else {
			status.setComplete();
			
			Mono<Categoria> categoria = service.findCategoriaById(producto.getCategoria().getId());
			
			return categoria.flatMap(c -> {
				if (producto.getCreateAt() == null) {
					producto.setCreateAt(new Date());
				}
				if(!file.filename().isEmpty()) {
					producto.setFoto(UUID.randomUUID().toString()+"-"+file.filename()
						.replace(" ","")
						.replace(":","")
						.replace("\\",""));
				}
				producto.setCategoria(c);
				return service.save(producto);
			}).doOnNext(p -> {
				log.info("Producto guardado: " + p.getNombre() + " Id: " + p.getId());
			})
			.flatMap(p -> {
				//Guardamos la foto
				if(!file.filename().isEmpty()) {
					return file.transferTo(new File(path+p.getFoto()));
				}
				return  Mono.empty();
			})		
			.thenReturn("redirect:/listar?success=producto+guardado+con+exito");
		}
	}

	@GetMapping("/eliminar/{id}")
	public Mono<String> eliminar(@PathVariable String id) {
		return service.findById(id).defaultIfEmpty(new Producto()).flatMap(p -> {
			if (p.getId() == null) {
				return Mono.error(new InterruptedException("No existe el producto a eliminar!"));
			}
			return Mono.just(p);
		}).flatMap(p -> service.delete(p)).thenReturn("redirect:/listar?success=producto+eliminado+con+exito")
		.onErrorResume(ex -> Mono.just("redirect:/listar?error=no+existe+el+producto+a+eliminar"));
	}

	@GetMapping({ "/listar-datadriver" })
	public String listarDataDriver(Model model) {
		Flux<Producto> productos = service.findAllConNombreUpperCase().delayElements(Duration.ofSeconds(1));

		productos.subscribe(prod -> log.info(prod.getNombre()));

		model.addAttribute("titulo", "Listado De Productos");
		model.addAttribute("productos", new ReactiveDataDriverContextVariable(productos, 2));
		return "listar";
	}

	@GetMapping("/listar-full")
	public String listarFull(Model model) {
		Flux<Producto> productos = service.findAllConNombreUpperCaseRepeat();
		model.addAttribute("titulo", "Listado De Productos");
		model.addAttribute("productos", productos);
		return "listar";
	}

	@GetMapping("/listar-chunked")
	public String listarChunked(Model model) {
		Flux<Producto> productos = service.findAllConNombreUpperCaseRepeat();

		model.addAttribute("titulo", "Listado De Productos");
		model.addAttribute("productos", productos);
		return "listar-chunked";
	}
}
