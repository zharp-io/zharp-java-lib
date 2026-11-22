package e2e.webflux;

import io.zharp.Zharp;
import io.zharp.ZharpUser;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@SpringBootApplication
@RestController
public class WebFluxApp {
    private static final Logger log = LoggerFactory.getLogger(WebFluxApp.class);

    public static void main(String[] args) {
        SpringApplication.run(WebFluxApp.class, args);
    }

    @GetMapping("/hello")
    public Mono<String> hello() {
        log.info("hello from the e2e app");
        return Mono.just("hi");
    }

    @GetMapping("/users/{id}")
    public Mono<Map<String, String>> user(@PathVariable String id) {
        Zharp.setUser(ZharpUser.of(id).withEmail(id + "@example.com"));
        return Mono.just(Map.of("id", id));
    }

    @GetMapping("/boom")
    public Mono<String> boom() {
        return Mono.error(new IllegalStateException("boom from the controller"));
    }

    @GetMapping("/handled")
    public Mono<String> handled() {
        try {
            Integer.parseInt("not a number");
        } catch (NumberFormatException e) {
            Zharp.captureError(e);
        }
        return Mono.just("handled");
    }

    @GetMapping("/logged")
    public Mono<String> logged() {
        try {
            Object value = null;
            value.toString();
        } catch (NullPointerException e) {
            log.error("could not read the value", e);
        }
        return Mono.just("logged");
    }

    @GetMapping("/thread-crash")
    public Mono<String> threadCrash() {
        new Thread(() -> {
            throw new UnsupportedOperationException("crash in a worker thread");
        }, "e2e-worker").start();
        return Mono.just("started");
    }

    @GetMapping("/exit")
    public Mono<String> exit() {
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }).start();
        return Mono.just("bye");
    }
}
