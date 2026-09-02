from dataclasses import dataclass
from enum import Enum


class FailureClass(str, Enum):
    TRANSIENT = "transient"
    RATE_LIMIT = "rate_limit"
    ACCESS_CONTROLLED = "access_controlled"
    UNSUPPORTED = "unsupported"
    PERMANENT = "permanent"


@dataclass(frozen=True)
class RetryDecision:
    retry: bool
    reason: str


def decide_failure(kind: FailureClass, attempt: int, max_attempts: int = 4) -> RetryDecision:
    if kind in {FailureClass.TRANSIENT, FailureClass.RATE_LIMIT} and attempt < max_attempts:
        return RetryDecision(True, "temporary failure; retry with backoff")
    if kind == FailureClass.ACCESS_CONTROLLED:
        return RetryDecision(False, "source requires access authorization; do not bypass access controls")
    return RetryDecision(False, "non-retryable source failure")
