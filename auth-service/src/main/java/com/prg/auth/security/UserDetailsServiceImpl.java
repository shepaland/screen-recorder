package com.prg.auth.security;

import com.prg.auth.entity.User;
import com.prg.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        throw new UsernameNotFoundException("Use login endpoint with tenant_slug for authentication");
    }

    @Transactional(readOnly = true)
    public UserPrincipal loadUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));

        List<String> roles = user.getRoles().stream()
                .map(r -> r.getCode())
                .toList();

        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .toList();

        List<String> scopes = determineScopesForRoles(roles);

        return UserPrincipal.builder()
                .userId(user.getId())
                .tenantId(user.getTenant().getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .roles(roles)
                .permissions(permissions)
                .scopes(scopes)
                .build();
    }

    private List<String> determineScopesForRoles(List<String> roles) {
        if (roles.contains("SUPER_ADMIN")) {
            return List.of("global");
        }
        if (roles.contains("OPERATOR")) {
            return List.of("own");
        }
        return List.of("tenant");
    }
}
