JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME

# colima puts its socket outside the default path. override if you run docker desktop.
DOCKER_HOST ?= unix://$(HOME)/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE ?= /var/run/docker.sock
export DOCKER_HOST
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE

.PHONY: build test run up down logs psql clean

build:
	mvn -B compile

test:
	mvn -B test

run:
	mvn -B spring-boot:run

up:
	docker compose up -d

down:
	docker compose down -v

logs:
	docker compose logs -f --tail=80

psql:
	docker exec -it ledger-postgres-1 psql -U ledger -d ledger

clean:
	mvn -B clean
