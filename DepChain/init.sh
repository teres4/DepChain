#!/usr/bin/env bash

SESSION="depchain"
SHELL_CMD=${SHELL:-/bin/zsh}

echo "Stopping and removing old containers..."
docker compose down --volumes --remove-orphans

echo "Rebuilding and starting containers..."
docker compose up --build -d

echo "Waiting for containers to be ready..."
sleep 3

echo "Starting tmux session with 4 replicas + 1 client..."

tmux kill-session -t $SESSION 2>/dev/null

tmux new-session -d -s $SESSION "$SHELL_CMD"

# Create 2x2 grid for 4 replicas
P1=$(tmux display-message -p '#{pane_id}')
P2=$(tmux split-window -h -P -F '#{pane_id}')

tmux select-pane -t $P1
P3=$(tmux split-window -v -P -F '#{pane_id}')

tmux select-pane -t $P2
P4=$(tmux split-window -v -P -F '#{pane_id}')

# Create client pane at bottom spanning full width
tmux select-pane -t $P1
P5=$(tmux split-window -v -l 30% -P -F '#{pane_id}')

# Adjust layout
tmux select-layout tiled

echo "Launching replicas..."

tmux send-keys -t $P1 "docker exec -it depchain_member1 mvn -q compile exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='1'" C-m
tmux send-keys -t $P2 "docker exec -it depchain_member2 mvn -q compile exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='2'" C-m
tmux send-keys -t $P3 "docker exec -it depchain_member3 mvn -q compile exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='3'" C-m
tmux send-keys -t $P4 "docker exec -it depchain_member4 mvn -q compile exec:java -Dexec.mainClass=pt.tecnico.sec.replica.ReplicaServer -Dexec.args='4'" C-m

echo "Waiting for replicas to initialize..."
sleep 5

echo "Launching client..."
tmux send-keys -t $P5 "docker exec -it depchain_client1 mvn -q compile exec:java -Dexec.mainClass=pt.tecnico.sec.client.Client -Dexec.args='100 9000'" C-m

tmux attach -t $SESSION