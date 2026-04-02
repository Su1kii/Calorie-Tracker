package dev.stev.calorie_tracker.domain.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String name;
    private String email;
    private String hashedPassword;
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_information_id")
    private UserInformation userInformation;
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Tracking> trackingHistory;

    // UserDetails methods Spring Security needs
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(); // no roles yet, empty list
    }

    @Override
    public String getPassword() {
        return hashedPassword;
    }

    @Override
    public String getUsername() {
        return email; // we use email as the unique identifier
    }
}