#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "Health"
curl -s "$BASE_URL/health"
echo

echo "Put user-1 name"
curl -s -X POST "$BASE_URL/kv?table=users&key=user-1&column=name" --data 'Suhas'
echo

echo "Put user-1 email"
curl -s -X POST "$BASE_URL/kv?table=users&key=user-1&column=email" --data 'suhas@example.com'
echo

echo "Put user-1 city"
curl -s -X POST "$BASE_URL/kv?table=users&key=user-1&column=city" --data 'Delhi NCR'
echo

echo "Read name"
curl -s "$BASE_URL/kv?table=users&key=user-1&column=name"
echo

echo "Read row"
curl -s "$BASE_URL/row?table=users&key=user-1"
echo

echo "Flush"
curl -s -X POST "$BASE_URL/admin/flush"
echo

echo "Compact"
curl -s -X POST "$BASE_URL/admin/compact"
echo

echo "Ring"
curl -s "$BASE_URL/cluster/ring"
echo

echo "Mark node-1 down"
curl -s -X POST "$BASE_URL/cluster/node/node-1/down"
echo

echo "Write while node-1 is down"
curl -s -X POST "$BASE_URL/kv?table=users&key=user-2&column=name" --data 'Alice'
echo

echo "Mark node-1 up; hints replay"
curl -s -X POST "$BASE_URL/cluster/node/node-1/up"
echo

echo "Cluster status"
curl -s "$BASE_URL/cluster/status"
echo
