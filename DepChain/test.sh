#!/usr/bin/env bash
set -e

echo "Stopping and removing old containers..."
docker compose down --volumes --remove-orphans

echo "Building fresh image (--no-cache ensures latest source is used)..."
docker build --no-cache -t depchain:latest .

echo "Starting containers..."
docker compose up -d

echo "Waiting for containers to be ready..."
sleep 2

echo "Running tests..."
docker exec -it depchain_member1 mvn test -Dsurefire.useFile=false
TEST_EXIT=$?

if [ $TEST_EXIT -eq 0 ]; then
    echo ""
    echo "✅ All tests passed!"
else
    echo ""
    echo "❌ Tests failed!"
fi

exit $TEST_EXIT