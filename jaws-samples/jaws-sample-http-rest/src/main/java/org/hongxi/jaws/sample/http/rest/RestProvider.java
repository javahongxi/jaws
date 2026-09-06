package org.hongxi.jaws.sample.http.rest;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.http.rest.service.UserServiceImpl;

/**
 * HTTP/1.1 REST provider — demonstrates annotation-driven REST mapping.
 * <p>
 * The {@link UserService} interface carries Spring Web annotations
 * ({@code @GetMapping}, {@code @PathVariable}, etc.) which Jaws scans
 * at export time to register RESTful routes on the HTTP server.
 * <p>
 * After startup, the following endpoints are available:
 * <pre>
 * # Health check (built-in)
 * curl -i http://localhost:10001/health
 *
 * # REST: Get user by path variable
 * curl http://localhost:10001/users/1
 *
 * # REST: List users with optional query param
 * curl http://localhost:10001/users
 * curl "http://localhost:10001/users?limit=1"
 *
 * # REST: Create user with JSON body
 * curl -X POST http://localhost:10001/users \
 *   -H "content-type: application/json" \
 *   -d '{"name":"test","age":20}'
 *
 * # Generic RPC invoke (still works as fallback)
 * curl -X POST http://localhost:10001/invoke \
 *   -H "content-type: application/json" \
 *   -d '{"interface":"org.hongxi.jaws.sample.http.rest.UserService","method":"getUser","args":[1]}'
 * </pre>
 *
 * @author shenhongxi
 */
public class RestProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10001"));

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("http");
        protocolConfig.setPort(PORT);

        ServiceConfig<UserService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new UserServiceImpl());
        serviceConfig.setApplication("sample-http-rest");
        serviceConfig.setModule("sample-rest");
        serviceConfig.setCheck(true);
        serviceConfig.setInterface(UserService.class);
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.export();

        System.out.println("UserService exported via HTTP/1.1 with REST mapping (transportFactory=http).");
        System.out.println("Provider listening on port " + PORT);
        System.out.println();
        System.out.println("Test with curl:");
        System.out.println("  curl -i http://localhost:" + PORT + "/health");
        System.out.println("  curl http://localhost:" + PORT + "/users/1");
        System.out.println("  curl http://localhost:" + PORT + "/users");
        System.out.println("  curl \"http://localhost:" + PORT + "/users?limit=1\"");
        System.out.println("  curl -X POST http://localhost:" + PORT + "/users \\");
        System.out.println("    -H \"content-type: application/json\" \\");
        System.out.println("    -d '{\"name\":\"test\",\"age\":20}'");
    }
}
