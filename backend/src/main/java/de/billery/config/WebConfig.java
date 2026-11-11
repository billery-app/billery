package de.billery.config;

import de.billery.data.AppProfiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the upload directory under {@code /uploads/**} and locks down how those user-controlled
 * files may be interpreted by the browser.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /**
   * Policy for everything under {@code /uploads/**}: an upload opened directly (also an SVG stored
   * before SVG uploads were rejected) is sandboxed into a unique origin without scripts, so it
   * cannot run code with the application's origin. Logos shown through {@code <img>} are
   * unaffected.
   */
  static final String UPLOAD_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (AppProfiles.isSaas()) {
      // Never serve the upload directory as static files: the SaaS profile serves per-tenant
      // files itself, after checking the signature of the URL.
      return;
    }
    registry
        .addResourceHandler("/uploads/**")
        .addResourceLocations(AppConfig.FILES_PATH.toAbsolutePath().toUri().toString());
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Content-Security-Policy", UPLOAD_CSP);
                return true;
              }
            })
        .addPathPatterns("/uploads/**");
  }
}
