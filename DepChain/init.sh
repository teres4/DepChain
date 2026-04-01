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

# ── Top half: 2x2 replica grid ──────────────────────────────────────────────

tmux split-window -v -t $SESSION:0.0 -p 35

# Split the top half (pane 0) into left and right columns
tmux split-window -h -t $SESSION:0.0

# Split left column (pane 0) into top and bottom
tmux split-window -v -t $SESSION:0.0

# Split right column (pane 2) into top and bottom
tmux split-window -v -t $SESSION:0.2

tmux send-keys -t $SESSION:0.0 "docker exec -it depchain_member1 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='1 0' 2>&1" C-m
tmux send-keys -t $SESSION:0.1 "docker exec -it depchain_member2 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='2 0' 2>&1" C-m

# Row 2 — Replicas 3 & 4
tmux send-keys -t $SESSION:0.2 "docker exec -it depchain_member3 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='3 0' 2>&1 | tee member3.log" C-m
tmux send-keys -t $SESSION:0.3 "docker exec -it depchain_member4 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='4 0' 2>&1" C-m

echo "Waiting for replicas to initialize..."
sleep 5

echo "Launching clients..."

tmux split-window -h -t $SESSION:0.4
tmux split-window -h -t $SESSION:0.4

tmux send-keys -t $SESSION:0.4 "docker exec -it depchain_client1 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='100 9000' 2>&1" C-m
tmux send-keys -t $SESSION:0.5 "docker exec -it depchain_client2 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='101 9001' 2>&1" C-m
tmux send-keys -t $SESSION:0.6 "docker exec -it depchain_client3 mvn -q exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='102 9002' 2>&1" C-m

tmux attach -t $SESSION