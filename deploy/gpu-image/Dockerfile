# A GPU-capable variant of the tester client image. IT DOES NOT WORK ON THIS HOST, AND THE
# REASON IS AT THE BOTTOM OF THIS COMMENT. Kept because it is the reproducible evidence for
# that, and the ready starting point if the host topology ever changes.
#
# WHY A DERIVED IMAGE RATHER THAN A CHANGE TO THE BASE ONE.
# mineswarm-mc:amd64 also backs the user's LIVE streamer containers. Rebuilding it to chase a
# bench optimisation would put those in the blast radius of a Mesa upgrade, which is not a
# trade worth making. This image is built FROM it, changes nothing in it, and is used only by
# deploy/compose.gpu.yml -- so the other project keeps running the exact bytes it runs today.
#
# WHAT IS ACTUALLY WRONG WITH THE BASE IMAGE, measured rather than assumed:
# The GPU is reachable (nvidia-smi answers, /dev/dxg is present, libd3d12core.so is mounted in
# from the Docker Desktop VM), and a GL context still cannot be created on it by either door:
#
#   GLX  -- GLFW error 65543: GLX: Failed to create context: GLXBadFBConfig
#   EGL  -- eglInitialize failed (0x3001), "egl: failed to create dri2 screen"
#
# The same EGL probe with GALLIUM_DRIVER=llvmpipe returns a working 4.5 context, so the probe
# is sound and the d3d12 driver specifically is what fails. That driver is Mesa 22.3.6 -- what
# Debian 12 pins, and far older than the point where d3d12-on-WSL became usable. Debian 12 has
# no newer candidate at all, so the fix is a newer Mesa, and trixie has 25.0.7.
#
# NVIDIA's own GLX is not an option here: on Docker Desktop + WSL2 the nvidia runtime injects
# COMPUTE and ENCODE only (libnvidia-encode, -ml, -ngx, -opticalflow), with no libGLX_nvidia,
# no libEGL_nvidia and no vendor ICD -- /usr/share/glvnd/egl_vendor.d holds only 50_mesa.json.
# So Mesa's d3d12 through /dev/dxg is the only graphics path that exists on this topology.
FROM mineswarm-mc:amd64

# Mesa 25 from trixie. This pulls a newer glibc with it, which is why it lives in a throwaway
# derived image and not in anything anyone depends on: if it breaks the client, the deploy
# discovers that within one attempt and puts the bench back on the CPU by itself.
USER root
RUN set -eux; \
    echo "deb http://deb.debian.org/debian trixie main" > /etc/apt/sources.list.d/trixie.list; \
    apt-get update -qq; \
    apt-get install -y --no-install-recommends -t trixie \
        libgl1-mesa-dri libglx-mesa0 libegl-mesa0 mesa-libgallium libgbm1; \
    rm -rf /var/lib/apt/lists/* /etc/apt/sources.list.d/trixie.list

# Left OFF here on purpose. compose.gpu.yml turns them on, so this image is still runnable on a
# machine with no GPU -- it simply behaves like the base one.
ENV LIBGL_ALWAYS_SOFTWARE=1
ENV GALLIUM_DRIVER=llvmpipe

# ⛔ RESULT: MESA 25 DOES NOT FIX IT EITHER, AND THE VERSION WAS NEVER THE PROBLEM.
#
# With 25.0.7 installed and d3d12_dri.so present, EGL fails in exactly the same place. Mesa's
# own debug output names it:
#
#     libEGL debug: EGL user error 0x3001 in eglInitialize: DRI2: failed to load driver
#     libEGL debug: Falling back to surfaceless swrast without DRM.
#
# "without DRM" is the whole finding. The container has NO /dev/dri AT ALL -- only /dev/dxg:
#
#     ls /dev/dri  ->  No such file or directory
#     ls /dev/dxg  ->  crw-rw-rw- 1 root root 10, 125
#
# Mesa's DRI2, GLX and surfaceless-EGL paths all instantiate a screen from a DRM device node.
# WSL2 does not expose one: the GPU arrives through dxgkrnl as /dev/dxg, which is not DRM. The
# only Mesa that can drive it is the one Microsoft ships inside WSLg, whose d3d12 driver is
# reached through a DXCore winsys and wired up to WSLg's own Wayland/X stack. Docker Desktop's
# VM is a WSL distro WITHOUT WSLg, so a container there gets the device node and nothing that
# can talk to it. Debian's generic Mesa builds d3d12_dri.so, and it dlopens cleanly (checked --
# it is a symlink to libdril_dri.so and all its deps resolve), but there is no winsys under it.
#
# So this is not a version, a driver or a configuration problem, and no amount of env-var
# tuning reaches it. It is the host topology. What WOULD change the answer:
#   - running the bench containers under a WSLg-enabled WSL distro rather than Docker Desktop's
#     VM, so WSLg's Mesa and its /mnt/wslg socket are available;
#   - a Docker Desktop that exposes a DRM node;
#   - a Linux host with a normal /dev/dri, where none of this applies in the first place.
# The first is an infrastructure choice for the owner of the box, not something the bench can
# decide for itself, which is why the deploy simply records the no and stays on the CPU.
