# Veritas

Veritas is a rental platform project focused on connecting renters and property owners.

## Current implementation

- Java 21 and Spring Boot backend
- PostgreSQL database
- Flyway database migrations
- Spring Data JPA
- Spring Security configuration
- User registration and OTP verification workflow
- Maven build

## Prerequisites

- JDK 21
- Docker Desktop with Docker Compose
- Git

## Backend setup

1. Open a terminal in the `backend` directory.
2. Configure the required environment variables using your local `.env` file or terminal environment.
3. Start the PostgreSQL service defined in `compose.yaml`.
4. Run the backend using the Maven wrapper:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

The backend configuration expects `POSTGRES_PASSWORD` to be supplied through the environment. Never commit real credentials or API keys.

## Database migrations

Flyway migrations are stored under:

`backend/src/main/resources/db/migration/`

## Project structure

```text
Veritas/
├── backend/
├── frontend/
├── ai-service/
├── docker/
├── docs/
├── scripts/
├── .gitignore
└── README.md
```

## Status

Development in progress. Features and setup instructions will evolve as implementation continues.
