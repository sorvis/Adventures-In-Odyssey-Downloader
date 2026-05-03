from fastapi import Header, HTTPException, status
from .config import AUTH_TOKEN


def require_token(authorization: str = Header(...)) -> None:
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or token != AUTH_TOKEN:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid bearer token")
