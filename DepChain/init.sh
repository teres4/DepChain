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
tmux kill-session -t $SESSION 2>/dev/null
tmux new-session -d -s $SESSION "$SHELL_CMD"

P1=$(tmux display-message -p '#{pane_id}')
P2=$(tmux split-window -h -P -F '#{pane_id}')
tmux select-pane -t $P1
P3=$(tmux split-window -v -P -F '#{pane_id}')
tmux select-pane -t $P2
P4=$(tmux split-window -v -P -F '#{pane_id}')
tmux select-layout tiled

echo "Running members..."

# No recompile needed - already compiled in Docker image
tmux send-keys -t $P1 "docker exec -it depchain_member1 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='1' 2>&1" C-m
tmux send-keys -t $P2 "docker exec -it depchain_member2 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='2' 2>&1" C-m
tmux send-keys -t $P3 "docker exec -it depchain_member3 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='3' 2>&1" C-m
tmux send-keys -t $P4 "docker exec -it depchain_member4 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='4' 2>&1" C-m

tmux attach -t $SESSION