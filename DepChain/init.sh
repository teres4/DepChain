#!/usr/bin/env bash
set -e

SESSION="depchain"
SHELL_CMD=${SHELL:-/bin/bash}

echo "Stopping and removing old containers..."
docker compose down --volumes --remove-orphans

echo "Building image (compile happens here)..."
docker build --no-cache -t depchain:latest .

echo "Starting containers..."
docker compose up -d

echo "Waiting for containers to be ready..."
sleep 2

echo "Starting tmux session..."
tmux kill-session -t $SESSION 2>/dev/null || true

tmux new-session -d -s $SESSION -x 220 -y 50
tmux split-window -h -t $SESSION
tmux split-window -v -t $SESSION:0.0
tmux split-window -v -t $SESSION:0.1

# Send commands to each pane
tmux send-keys -t $SESSION:0.0 "docker exec -it depchain_member1 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='1' 2>&1" C-m
tmux send-keys -t $SESSION:0.1 "docker exec -it depchain_member2 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='2' 2>&1" C-m
tmux send-keys -t $SESSION:0.2 "docker exec -it depchain_member3 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='3' 2>&1" C-m
tmux send-keys -t $SESSION:0.3 "docker exec -it depchain_member4 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='4 --byzantine' 2>&1" C-m

tmux select-layout -t $SESSION tiled

tmux attach -t $SESSION