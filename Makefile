.PHONY: run test lint fmt up down logs

MVNW := ./mvnw -B

## run: start Postgres in Docker, then the app on the host (http://localhost:8080)
run:
	docker compose up -d postgres
	$(MVNW) -pl seat-service spring-boot:run

## test: full build with unit, integration (Testcontainers) and concurrency tests, Spotless and Checkstyle
test:
	$(MVNW) verify

## lint: formatting and static checks only
lint:
	$(MVNW) spotless:check checkstyle:check

## fmt: apply formatting
fmt:
	$(MVNW) spotless:apply

## up: build and run app + Postgres exactly as a fresh clone would
up:
	docker compose up --build -d
	@echo "waiting for readiness..."
	@until curl -fsS http://localhost:$${APP_HOST_PORT:-8080}/readyz >/dev/null; do sleep 1; done
	@echo "ready: http://localhost:$${APP_HOST_PORT:-8080}"

## down: stop everything and delete the local database volume
down:
	docker compose down -v

## logs: follow app logs (JSON, one line per event)
logs:
	docker compose logs -f app
