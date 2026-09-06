# Staging

Постоянный staging/test stand намеренно не создаётся по ADR 0012. Pull request
проверяется CI и disposable dependencies, development запускается локально, а
единственным cloud environment остаётся `production`.

Добавление staging требует отдельного решения и собственного isolated state,
secrets, service identities и бюджета; production resources переиспользовать
нельзя.
