package de.billery.config;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built SPA (the {@code static/} folder of the JAR).
 *
 * <p>Caching follows the build: Vite names every file under {@code assets/} after its content, so
 * those files never change and may be cached for a year; a request for one that does not exist
 * answers 404. {@code index.html}, which names the current asset files, is never cached, and every
 * other path that is not an API path answers with it (the SPA routes in the browser). Before, the
 * start page {@code /} had no cache header and a missing asset was answered with {@code
 * index.html}: after a new deployment a browser could keep the old start page, ask for asset files
 * that no longer exist, receive HTML instead of JavaScript and show a white page.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

  private static final String INDEX = "/static/index.html";

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // The start page goes through the resource handler below, so it gets its cache header.
    registry.addViewController("/").setViewName("forward:/index.html");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                Resource resource = super.getResource(resourcePath, location);
                if (resource != null && resource.exists()) {
                  return resource;
                }
                if (!resourcePath.startsWith("api") && !resourcePath.startsWith("assets/")) {
                  return new ClassPathResource(INDEX);
                }
                return null;
              }
            });
  }
}
