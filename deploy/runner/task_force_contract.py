"""Compile and exercise the actual Task lifecycle without launching Minecraft.

Requires Docker and the repository's Linux JDK. Stubs replace only logging, the
chain's recording sink and the unrelated timeout-wander dependency, never Task
or ITaskCanForce. Generated files stay under ignored artifacts.
"""
import argparse
import pathlib
from uctest import process as subprocess
import tempfile


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=pathlib.Path, help='Verify Task from a built jar instead of compiling Task.java.')
    args = parser.parse_args()
    jar = args.jar.resolve() if args.jar else None
    if jar is not None and not jar.is_file():
        parser.error('Jar does not exist.')
    artifacts = root / 'deploy/runner/artifacts'
    artifacts.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='task-force-', dir=artifacts) as directory:
        case = pathlib.Path(directory)
        stubs = {
            'adris/altoclef/Debug.java': '''package adris.altoclef;
public final class Debug { public static void logInternal(String message) {} }''',
            'adris/altoclef/tasksystem/TaskChain.java': '''package adris.altoclef.tasksystem;
public final class TaskChain { public void addTaskToChain(Task task) {} }''',
            'adris/altoclef/tasks/movement/TimeoutWanderTask.java': '''package adris.altoclef.tasks.movement;
import adris.altoclef.tasksystem.Task;
public final class TimeoutWanderTask extends Task {
protected void onStart() {} protected Task onTick() { return null; }
protected void onStop(Task next) {} protected boolean isEqual(Task other) { return other == this; }
protected String toDebugString() { return "stub wander"; }
}''',
        }
        for name, content in stubs.items():
            path = case / 'src' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        base = ['docker', 'run', '--rm', '-v', f'{root.as_posix()}:/w:ro',
                '-v', f'{case.as_posix()}:/case', '-w', '/case', 'debian:bookworm-slim']
        compile_args = ['-d', 'classes', '-sourcepath', 'src']
        runtime_path = 'classes'
        if jar is None:
            compile_args += ['/w/src/main/java/adris/altoclef/tasksystem/Task.java',
                             '/w/src/main/java/adris/altoclef/tasksystem/ITaskCanForce.java']
        else:
            base[3:3] = ['-v', f'{jar.as_posix()}:/candidate.jar:ro']
            compile_args += ['-classpath', '/candidate.jar']
            runtime_path += ':/candidate.jar'
        compile_args += ['src/' + name for name in stubs]
        compile_args += ['/w/deploy/runner/contracts/TaskForceContract.java']
        subprocess.run(base + ['/w/.gradle/jdk21/bin/javac'] + compile_args, check=True)
        subprocess.run(base + ['/w/.gradle/jdk21/bin/java', '-cp', runtime_path,
                               'TaskForceContract'], check=True)


if __name__ == '__main__':
    main()
