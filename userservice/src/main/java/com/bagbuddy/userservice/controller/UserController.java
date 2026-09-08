package com.bagbuddy.userservice.controller;

import com.bagbuddy.userservice.dto.UserDTO;
import com.bagbuddy.userservice.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDTO> getUserById(@PathVariable String id) {
        try {
            UserDTO user = userService.getUserById(id);
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<UserDTO> createUser(@RequestBody UserDTO userDTO) {
        try {
            UserDTO createdUser = userService.createUser(userDTO);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDTO> updateUser(
            @PathVariable String id,
            @RequestBody UserDTO userDTO) {
        try {
            UserDTO updatedUser = userService.updateUser(id, userDTO);
            return ResponseEntity.ok(updatedUser);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        try {
            userService.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<Void> syncUser(@PathVariable String id) {
        try {
            userService.syncUserFromKeycloak(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}