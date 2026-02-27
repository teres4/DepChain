#!/usr/bin/env bash

SESSION="depchain"
SHELL_CMD=${SHELL:-/bin/zsh}

echo "Stopping and removing old containers..."
docker compose down --volumes --remove-orphans

echo "Rebuilding and starting containers..."
docker compose up --build -d

echo "Starting tmux session..."

tmux kill-session -t $SESSION 2>/dev/null

tmux new-session -d -s $SESSION "$SHELL_CMD"

# Pane 0
P1=$(tmux display-message -p '#{pane_id}')

# Split right
P2=$(tmux split-window -h -P -F '#{pane_id}')

# Split bottom-left
tmux select-pane -t $P1
P3=$(tmux split-window -v -P -F '#{pane_id}')

# Split bottom-right
tmux select-pane -t $P2
P4=$(tmux split-window -v -P -F '#{pane_id}')

tmux select-layout tiled

echo "Running members..."

tmux send-keys -t $P1 "docker exec -it depchain_member1 mvn exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='1 ./config/keys/private_key1.pem'" C-m
tmux send-keys -t $P2 "docker exec -it depchain_member2 mvn exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='2 ./config/keys/private_key2.pem'" C-m
tmux send-keys -t $P3 "docker exec -it depchain_member3 mvn exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='3 ./config/keys/private_key3.pem'" C-m
tmux send-keys -t $P4 "docker exec -it depchain_member4 mvn exec:java -Dexec.mainClass=pt.tecnico.sec.App -Dexec.args='4 ./config/keys/private_key4.pem'" C-m

tmux attach -t $SESSION