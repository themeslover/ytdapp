from dataclasses import dataclass
import asyncio

from .download_policy import FailureClass, decide_failure


@dataclass
class AttemptResult:
    ok: bool
    value: object | None = None
    failure: FailureClass | None = None
    message: str = ""


async def run_with_retries(operation, max_attempts: int = 4) -> AttemptResult:
    for attempt in range(max_attempts + 1):
        result: AttemptResult = await operation()
        if result.ok:
            return result
        failure = result.failure or FailureClass.PERMANENT
        decision = decide_failure(failure, attempt, max_attempts)
        if not decision.retry:
            return AttemptResult(False, failure=failure, message=decision.reason)
        await asyncio.sleep(min(15, 0.75 * (2 ** attempt)))
    return AttemptResult(False, failure=FailureClass.PERMANENT, message="retry budget exhausted")
