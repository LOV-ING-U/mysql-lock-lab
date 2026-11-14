set pagination off
set confirm off
set breakpoint pending off
set print thread-events off
set print inferior-events off
handle SIGPIPE nostop noprint pass
handle SIGALRM nostop noprint pass
handle SIGUSR1 nostop noprint pass
handle SIGUSR2 nostop noprint pass
handle SIGHUP nostop noprint pass
handle SIGTERM nostop noprint pass
python
import os
import sys
sys.path.insert(0, os.environ.get('LOCKLAB_TRACER_DIR', '/opt/tracer'))
import gdb_trace
gdb_trace.main()
end
