package e2e.servlet;

import io.zharp.Zharp;
import io.zharp.ZharpUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;

/** The e2e routes as plain servlets on embedded Jetty, with plain JDBC and Log4j 2. */
public class ServletApp {
    private static final Logger log = LogManager.getLogger(ServletApp.class);
    static final String DB = "jdbc:postgresql://localhost:" + env("PG_PORT", "15432") + "/e2e";

    public static void main(String[] args) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("drop table if exists users");
            s.execute("create table users (id serial primary key, email text not null unique)");
        }
        Server server = new Server(Integer.parseInt(env("PORT", "18085")));
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(Hello.class, "/hello");
        context.addServlet(Users.class, "/users/*");
        context.addServlet(Users.class, "/users");
        context.addServlet(Boom.class, "/boom");
        context.addServlet(DuplicateCaught.class, "/dup-caught");
        context.addServlet(Handled.class, "/handled");
        context.addServlet(Logged.class, "/logged");
        context.addServlet(ThreadCrash.class, "/thread-crash");
        context.addServlet(Exit.class, "/exit");
        server.setHandler(context);
        server.start();
        log.info("Listening");
        server.join();
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB, "e2e", "e2e");
    }

    static void insert(String email) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("insert into users(email) values (?)")) {
            p.setString(1, email);
            p.executeUpdate();
        }
    }

    public static class Hello extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            log.info("hello from the e2e app");
            resp.getWriter().write("hi");
        }
    }

    public static class Users extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String id = req.getPathInfo() == null ? "" : req.getPathInfo().substring(1);
            Zharp.setUser(ZharpUser.of(id).withEmail(id + "@example.com"));
            resp.getWriter().write("{\"id\":\"" + id + "\"}");
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
            try {
                insert(req.getParameter("email"));
            } catch (SQLException e) {
                throw new ServletException(e);
            }
            resp.getWriter().write("created");
        }
    }

    public static class Boom extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            throw new IllegalStateException("boom from the controller");
        }
    }

    public static class DuplicateCaught extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try {
                insert("caught@example.com");
                insert("caught@example.com");
                resp.getWriter().write("no duplicate?");
            } catch (SQLException e) {
                resp.setStatus(409);
                resp.getWriter().write("duplicate");
            }
        }
    }

    public static class Handled extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try {
                Integer.parseInt("not a number");
            } catch (NumberFormatException e) {
                Zharp.captureError(e);
            }
            resp.getWriter().write("handled");
        }
    }

    public static class Logged extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try {
                Object value = null;
                value.toString();
            } catch (NullPointerException e) {
                log.error("could not read the value", e);
            }
            resp.getWriter().write("logged");
        }
    }

    public static class ThreadCrash extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            new Thread(() -> {
                throw new UnsupportedOperationException("crash in a worker thread");
            }, "e2e-worker").start();
            resp.getWriter().write("started");
        }
    }

    public static class Exit extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                System.exit(0);
            }).start();
            resp.getWriter().write("bye");
        }
    }
}
