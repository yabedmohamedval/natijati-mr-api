package mr.natijati.results.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class PublicCacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isPublicCacheablePath(path)) {
            response.setHeader(
                    "Cache-Control",
                    "public, max-age=300, s-maxage=300"
            );
        } else if (isSensitivePublicPath(path)) {
            response.setHeader(
                    "Cache-Control",
                    "no-store"
            );
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicCacheablePath(String path) {
        return path.equals("/api/public/catalog/exams")
                || path.startsWith("/api/public/catalog/statistics/")
                || path.equals("/api/public/catalog/institutions")
                || path.startsWith("/api/public/catalog/institutions/");
    }

    private boolean isSensitivePublicPath(String path) {
        return path.startsWith("/api/public/catalog/search")
                || path.startsWith("/api/public/results/");
    }
}