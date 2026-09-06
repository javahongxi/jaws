package org.hongxi.jaws.sample.http.rest;

import org.hongxi.jaws.sample.api.model.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Service interface demonstrating Spring Web annotation-driven REST mapping.
 * <p>
 * When exposed via the HTTP transport ({@code transportFactory=http}), Jaws
 * scans these annotations and registers RESTful routes. The same service
 * remains callable via the generic {@code POST /invoke} endpoint.
 * <p>
 * After startup, test with:
 * <pre>
 * # Get user by ID (path variable)
 * curl http://localhost:10001/users/1
 *
 * # List users with optional query param
 * curl http://localhost:10001/users
 * curl "http://localhost:10001/users?limit=1"
 *
 * # Create user (JSON body)
 * curl -X POST http://localhost:10001/users \
 *   -H "content-type: application/json" \
 *   -d '{"name":"test","age":20}'
 * </pre>
 *
 * @author shenhongxi
 */
@RequestMapping("/users")
public interface UserService {

    @GetMapping("/{id}")
    User getUser(@PathVariable("id") Long id);

    @GetMapping
    List<User> listUsers(@RequestParam(value = "limit", required = false) Integer limit);

    @PostMapping
    User createUser(@RequestBody User user);
}
