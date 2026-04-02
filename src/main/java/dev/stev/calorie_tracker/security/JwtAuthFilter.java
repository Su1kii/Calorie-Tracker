package dev.stev.calorie_tracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Grab the Authorization header from the request
        final String authHeader = request.getHeader("Authorization");

        // If there's no header, or it doesn't start with "Bearer", skip this filter
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract just the token part (cut off "Bearer")
        final String jwt = authHeader.substring(7);

        // Extract the email from inside the token
        final String email = jwtService.extractEmail(jwt);

        // If we got an email and the user isn't already authenticated
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            // Load the user from the database
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            // Validate the token against the loaded user
            if (jwtService.isTokenValid(jwt, userDetails.getUsername())) {

                // Create an authentication object and put it in Spring's SecurityContext
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        // Pass the request along to the next filter or controller
        filterChain.doFilter(request, response);
    }
}
