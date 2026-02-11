# curl Basics

**curl** is a command-line tool for transferring data using URLs.

## Basic Syntax

```bash
curl [options] <URL>
```

## Common Options

| Option | Description |
|--------|-------------|
| `-X` | Specify HTTP method (POST, PUT, PATCH, DELETE) |
| `-H` | Add header |
| `-d` | Send data (body) |
| `-i` | Include response headers |
| `-v` | Verbose output |
| `-o` | Save output to file |

## HTTP Methods

### GET (default - no `-X` needed)

```bash
curl http://localhost:8080/health
```

### POST

```bash
curl -X POST http://localhost:8080/health/down
```

### PUT

```bash
curl -X PUT http://localhost:8080/api/users/1
```

### PATCH

```bash
curl -X PATCH http://localhost:8080/api/users/1
```

### DELETE

```bash
curl -X DELETE http://localhost:8080/api/users/1
```

## When Do You Need `-X`?

| Method | Need `-X`? |
|--------|-----------|
| GET | No (default) |
| POST | Yes (or use `-d`) |
| PUT | Yes |
| PATCH | Yes |
| DELETE | Yes |

## Sending Data

### POST with JSON body

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"John","email":"john@example.com"}'
```

### Shortcut: `-d` implies POST

```bash
# These are equivalent
curl -X POST -d '{"name":"test"}' http://localhost:8080/api/users
curl -d '{"name":"test"}' http://localhost:8080/api/users
```

> **Note:** `-d` only auto-sets POST. For PUT/PATCH, always use `-X`.

## Adding Headers

### Correct syntax

```bash
curl http://localhost:8080/echo-state -H "X-Client-State: 123"
```

### Wrong syntax (common mistake)

```bash
# WRONG - colon outside quotes
curl http://localhost:8080/echo-state -H "X-Client-State": "123"
```

This causes curl to interpret `"123"` as a separate URL!

## Debugging

### See response headers

```bash
curl -i http://localhost:8080/health
```

### Verbose mode (full request/response)

```bash
curl -v http://localhost:8080/health
```

## Real-World Header Examples

| Header | Purpose |
|--------|---------|
| `Authorization` | Bearer tokens, API keys |
| `X-Request-ID` | Trace requests across services |
| `X-Correlation-ID` | Link related requests |
| `X-Device-ID` | Identify device |
| `Content-Type` | Specify body format (e.g., `application/json`) |

### Examples

```bash
# Authentication
curl http://localhost:8080/api/profile -H "Authorization: Bearer abc123xyz"

# Custom client state
curl http://localhost:8080/api/data -H "X-Client-State: theme=dark;lang=en"

# Request tracing
curl http://localhost:8080/api/orders -H "X-Request-ID: req-12345"
```

## Summary

- GET is default, no `-X` needed
- Use `-X` for POST, PUT, PATCH, DELETE
- `-d` automatically uses POST
- Keep header name and value inside the same quotes: `-H "Name: Value"`
- Use `-v` to debug request/response details
