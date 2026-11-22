package e2e.mvc;

import io.zharp.Zharp;
import io.zharp.ZharpUser;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The routes every e2e app has (see e2e/run.mjs). */
@RestController
public class Routes {
    private static final Logger log = LoggerFactory.getLogger(Routes.class);
    private final JdbcTemplate db;

    public Routes(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/hello")
    public String hello() {
        log.info("hello from the e2e app");
        return "hi";
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable String id) {
        Zharp.setUser(ZharpUser.of(id).withEmail(id + "@example.com"));
        return Map.of("id", id, "count", db.queryForObject("select count(*) from users", Integer.class));
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("boom from the controller");
    }

    @PostMapping("/users")
    public String create(@RequestParam String email) {
        // The second call with the same email fails the request with a unique violation.
        db.update("insert into users(email) values (?)", email);
        return "created";
    }

    @GetMapping("/dup-caught")
    public ResponseEntity<String> duplicateCaught() {
        try {
            db.update("insert into users(email) values (?)", "caught@example.com");
            db.update("insert into users(email) values (?)", "caught@example.com");
            return ResponseEntity.ok("no duplicate?");
        } catch (DataAccessException e) {
            return ResponseEntity.status(409).body("duplicate");
        }
    }

    @GetMapping("/handled")
    public String handled() {
        try {
            Integer.parseInt("not a number");
        } catch (NumberFormatException e) {
            Zharp.captureError(e);
        }
        return "handled";
    }

    @GetMapping("/logged")
    public String logged() {
        try {
            Object value = null;
            value.toString();
        } catch (NullPointerException e) {
            log.error("could not read the value", e);
        }
        return "logged";
    }

    @GetMapping("/thread-crash")
    public String threadCrash() {
        Thread worker = new Thread(() -> {
            throw new UnsupportedOperationException("crash in a worker thread");
        }, "e2e-worker");
        worker.start();
        return "started";
    }

    @GetMapping("/exit")
    public String exit() {
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }).start();
        return "bye";
    }
}
