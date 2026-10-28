/*
 * API base address.
 * Leave it EMPTY when the pages are served by the Spring Boot application (default: http://localhost:8080/).
 * Only if you open the frontend from a different address (for example VS Code "Live Server" on port 5500)
 * set it to the backend address, e.g.   window.API_BASE = 'http://localhost:8080';
 * (the backend then allows that origin through CORS - see app.cors.allowed-origins in application.properties)
 */
window.API_BASE = '';
