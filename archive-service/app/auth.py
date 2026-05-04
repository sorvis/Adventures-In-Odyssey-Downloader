from fastapi import Header, HTTPException, status

from .config import AUTH_TOKEN


def require_token(authorization: str | None = Header(default=None)) -> None:
    """Validate the Authorization header. Returns 401 (not 422) when
    the header is missing OR malformed OR the token is wrong, so the
    Android NasClient and any other consumer get a consistent failure
    code regardless of which way auth was rejected."""
    if not authorization:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing bearer token")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or token != AUTH_TOKEN:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid bearer token")
