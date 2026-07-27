#!/bin/sh
set -eu

lattex_jar=/opt/lattex/lattex.jar
worker_jar=/opt/lattex/lattex-worker.jar

case "${1-}" in
    watch)
        shift
        if [ "$#" -ne 0 ]; then
            echo "lattex: watch mode takes no arguments" >&2
            exit 2
        fi
        exec java -cp "$lattex_jar:$worker_jar" \
            com.lattex.cli.LatteXFolderWorker
        ;;
    cli)
        shift
        if [ "${1-}" = "--input" ]; then
            shift
            if [ "$#" -lt 1 ]; then
                echo "lattex: cli --input requires a FILE argument" >&2
                exit 2
            fi
            input_file=$1
            shift
            if [ ! -f "$input_file" ] || [ ! -r "$input_file" ]; then
                echo "lattex: cli input is not a readable regular file" >&2
                exit 1
            fi
            exec java -jar "$lattex_jar" "$@" < "$input_file"
        fi
        exec java -jar "$lattex_jar" "$@"
        ;;
    *)
        # Compatibility shape: arguments that do not name a container mode go
        # straight to the shipped CLI. With no arguments, stdin -> stdout is
        # byte-for-byte the original one-shot contract.
        exec java -jar "$lattex_jar" "$@"
        ;;
esac
