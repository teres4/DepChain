#!/usr/bin/env bash
set -e

SESSION="depchain"

echo "Stopping and removing old containers..."
docker compose down --volumes --remove-orphans

echo "Building image (compile happens here)..."
docker build -t depchain:latest .

echo "Starting containers..."
docker compose up -d

echo "Waiting for containers to be ready..."
sleep 2

echo "Starting tmux session..."
tmux kill-session -t $SESSION 2>/dev/null || true

tmux new-session -d -s $SESSION -x 220 -y 50

# Build layout top-down, left-right
# Start: pane 0 (full window)

# Create 3 rows by splitting vertically twice from the bottom pane each time
tmux split-window -v -t $SESSION:0.0   # now: 0.0 (top), 0.1 (bottom)
tmux split-window -v -t $SESSION:0.1   # now: 0.0 (top), 0.1 (middle), 0.2 (bottom)

# Split top row → replicas 1 & 2
tmux split-window -h -t $SESSION:0.0   # now: 0.0, 0.1 (top row), 0.2 (middle), 0.3 (bottom)

# Split middle row → replicas 3 & 4
tmux split-window -h -t $SESSION:0.2   # now: 0.0, 0.1 (top), 0.2, 0.3 (middle), 0.4 (bottom)

# Split bottom row → 3 clients
tmux split-window -h -t $SESSION:0.4   # bottom: 0.4, 0.5
tmux split-window -h -t $SESSION:0.4   # bottom: 0.4, 0.5, 0.6 (splits leftmost again to keep order)

# Equalize heights across the 3 rows
tmux select-layout -t $SESSION even-vertical

# Row 1 — Replicas 1 & 2
tmux send-keys -t $SESSION:0.0 "docker exec -it depchain_member1 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='1 0' 2>&1" C-m
tmux send-keys -t $SESSION:0.1 "docker exec -it depchain_member2 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='2 0' 2>&1" C-m

# Row 2 — Replicas 3 & 4
tmux send-keys -t $SESSION:0.2 "docker exec -it depchain_member3 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='3 0' 2>&1 | tee member3.log" C-m
tmux send-keys -t $SESSION:0.3 "docker exec -it depchain_member4 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='4 0' 2>&1" C-m

echo "Waiting for replicas to initialize..."
sleep 5

echo "Launching clients..."

# Row 3 — Clients
tmux send-keys -t $SESSION:0.4 "docker exec -it depchain_client1 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='100 9000' 2>&1" C-m
tmux send-keys -t $SESSION:0.5 "docker exec -it depchain_client2 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='101 9001' 2>&1" C-m
tmux send-keys -t $SESSION:0.6 "docker exec -it depchain_client3 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='102 9002' 2>&1" C-m

tmux select-layout -t $SESSION tiled

tmux attach -t $SESSION