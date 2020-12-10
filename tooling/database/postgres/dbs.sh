#!/usr/bin/env bash
# This script is necessary to run integration tests
host=$1
port=$2
user=$3
pwd=$4
prefix=$5
drop_mode=$6

export PGPASSWORD=$pwd

command=createdb
if [ -n "$drop_mode" ]; then
echo "!!!!!!!!!!!!!!!!!!!!!"
echo "!!DROPPING DBS MODE!!"
echo "!!!!!!!!!!!!!!!!!!!!!"
command=dropdb
fi

echo "==> PostgreSQL server infos: host " $host ", port " $port

echo "     * Creating database : wallet_daemon"
$command -h $host -p $port wallet_daemon -U $user
echo "     |--> wallet_daemon Done"

# Databases used in IT tests
tests=(default_test_pool create_delete_pool walletpooldao databasedaotest account_test pool_a pool_b pool_c empty_pool walletpool_test account_api_test_pool tez_test_pool)

# Create all DBs with names above
for elem in "${tests[@]}"
do
    echo "     * Creating database : $prefix$elem"
    $command -h $host -p $port $prefix$elem -U $user
    echo "     |--> $prefix$elem Done"

done




