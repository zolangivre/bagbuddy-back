package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.dto.UserDTO;
import com.bagbuddy.userservice.model.User;
import com.bagbuddy.userservice.repository.UserRepository;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final Keycloak keycloak;
    private final UserRepository userRepository;
    private final String realm = "bagbuddy";

    public UserService(Keycloak keycloak, UserRepository userRepository) {
        this.keycloak = keycloak;
        this.userRepository = userRepository;
    }

    private RealmResource getRealmResource() {
        return keycloak.realm(realm);
    }

    private UsersResource getUsersResource() {
        return getRealmResource().users();
    }

    /**
     * Récupère tous les utilisateurs depuis Keycloak
     */
    public List<UserDTO> getAllUsers() {
        return getUsersResource().list().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Récupère un utilisateur par son ID Keycloak
     */
    public UserDTO getUserById(String keycloakId) {
        UserResource userResource = getUsersResource().get(keycloakId);
        UserRepresentation userRep = userResource.toRepresentation();
        return toDTO(userRep);
    }

    /**
     * Crée un utilisateur dans Keycloak ET dans la base de données locale
     */
    @Transactional
    public UserDTO createUser(UserDTO userDTO) {
        // 1. Créer l'utilisateur dans Keycloak
        UserRepresentation userRep = new UserRepresentation();
        userRep.setUsername(userDTO.getUsername());
        userRep.setEmail(userDTO.getEmail());
        userRep.setFirstName(userDTO.getFirstName());
        userRep.setLastName(userDTO.getLastName());
        userRep.setEnabled(true);
        userRep.setEmailVerified(false);

        Response response = getUsersResource().create(userRep);

        if (response.getStatus() != 201) {
            throw new RuntimeException("Échec de la création de l'utilisateur dans Keycloak: " + response.getStatusInfo());
        }

        // Récupérer l'ID Keycloak de l'utilisateur créé
        String locationHeader = response.getHeaderString("Location");
        String keycloakId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        response.close();

        // 2. Définir le mot de passe
        if (userDTO.getPassword() != null && !userDTO.getPassword().isEmpty()) {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(userDTO.getPassword());
            credential.setTemporary(false);

            getUsersResource().get(keycloakId).resetPassword(credential);
        }

        // 3. Sauvegarder dans la base de données locale
        User user = new User();
        user.setSub(keycloakId);
        user.setEmail(userDTO.getEmail());
        user.setUsername(userDTO.getUsername());
        user.setFamilyName(userDTO.getLastName());
        user.setGivenName(userDTO.getFirstName());
        user.setName(userDTO.getFirstName() + " " + userDTO.getLastName());
        user.setBio(userDTO.getBio());
        user.setLocation(userDTO.getLocation());
        user.setPhone(userDTO.getPhone());
        user.setEmailVerified(false);

        userRepository.save(user);

        userDTO.setId(keycloakId);
        return userDTO;
    }

    /**
     * Met à jour un utilisateur dans Keycloak ET dans la base de données locale
     */
    @Transactional
    public UserDTO updateUser(String keycloakId, UserDTO userDTO) {
        // 1. Mise à jour dans Keycloak
        UserResource userResource = getUsersResource().get(keycloakId);
        UserRepresentation userRep = userResource.toRepresentation();

        if (userDTO.getEmail() != null) {
            userRep.setEmail(userDTO.getEmail());
        }
        if (userDTO.getFirstName() != null) {
            userRep.setFirstName(userDTO.getFirstName());
        }
        if (userDTO.getLastName() != null) {
            userRep.setLastName(userDTO.getLastName());
        }
        if (userDTO.getUsername() != null) {
            userRep.setUsername(userDTO.getUsername());
        }

        userResource.update(userRep);

        // 2. Mise à jour dans la base de données locale
        User user = userRepository.findBySub(keycloakId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé dans la base locale"));

        if (userDTO.getEmail() != null) user.setEmail(userDTO.getEmail());
        if (userDTO.getUsername() != null) user.setUsername(userDTO.getUsername());
        if (userDTO.getFirstName() != null) user.setGivenName(userDTO.getFirstName());
        if (userDTO.getLastName() != null) user.setFamilyName(userDTO.getLastName());
        if (userDTO.getBio() != null) user.setBio(userDTO.getBio());
        if (userDTO.getLocation() != null) user.setLocation(userDTO.getLocation());
        if (userDTO.getPhone() != null) user.setPhone(userDTO.getPhone());

        if (userDTO.getFirstName() != null || userDTO.getLastName() != null) {
            user.setName(user.getGivenName() + " " + user.getFamilyName());
        }

        userRepository.save(user);

        return toDTO(userResource.toRepresentation());
    }

    /**
     * Supprime un utilisateur de Keycloak ET de la base de données locale
     */
    @Transactional
    public void deleteUser(String keycloakId) {
        // 1. Supprimer de Keycloak
        Response response = getUsersResource().delete(keycloakId);

        if (response.getStatus() != 204) {
            throw new RuntimeException("Échec de la suppression dans Keycloak");
        }
        response.close();

        // 2. Supprimer de la base de données locale
        userRepository.findBySub(keycloakId).ifPresent(userRepository::delete);
    }

    /**
     * Synchronise un utilisateur Keycloak avec la base de données locale
     */
    @Transactional
    public void syncUserFromKeycloak(String keycloakId) {
        UserRepresentation userRep = getUsersResource().get(keycloakId).toRepresentation();

        User user = userRepository.findBySub(keycloakId).orElse(new User());
        user.setSub(keycloakId);
        user.setEmail(userRep.getEmail());
        user.setUsername(userRep.getUsername());
        user.setFamilyName(userRep.getLastName());
        user.setGivenName(userRep.getFirstName());
        user.setName(userRep.getFirstName() + " " + userRep.getLastName());
        user.setEmailVerified(userRep.isEmailVerified());

        userRepository.save(user);
    }

    /**
     * Convertit UserRepresentation en UserDTO
     */
    private UserDTO toDTO(UserRepresentation userRep) {
        UserDTO dto = new UserDTO();
        dto.setId(userRep.getId());
        dto.setEmail(userRep.getEmail());
        dto.setUsername(userRep.getUsername());
        dto.setFirstName(userRep.getFirstName());
        dto.setLastName(userRep.getLastName());

        // Récupérer les infos additionnelles de la base locale si disponibles
        userRepository.findBySub(userRep.getId()).ifPresent(user -> {
            dto.setBio(user.getBio());
            dto.setLocation(user.getLocation());
            dto.setPhone(user.getPhone());
        });

        return dto;
    }
}