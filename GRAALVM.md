# Proxy Sidecar GraalVM Native Image

By default, we build a GraalVM native image for Linux. This is done inside a Docker container and so can be done on any platform with Docker installed. We also generate Docker images containing the native image, so the native image can be run on any platform using Docker too.

## Building the native image

If you simply want to run the native image locally, you can use the following command:

```
sbt "dockerBuildDevMode publishLocal"
```

This will take 5 or more minutes. Among the output you should see the result of the native image build, for example:

```bash
…
[info] [cloudstate-proxy-core:34956]    classlist:  23,867.63 ms
[info] [cloudstate-proxy-core:34956]        (cap):   1,402.66 ms
[info] [cloudstate-proxy-core:34956]        setup:   3,183.02 ms
[info] [cloudstate-proxy-core:34956]   (typeflow): 106,110.90 ms
[info] [cloudstate-proxy-core:34956]    (objects):  62,919.78 ms
[info] [cloudstate-proxy-core:34956]   (features):   8,452.19 ms
[info] [cloudstate-proxy-core:34956]     analysis: 185,664.30 ms
[info] [cloudstate-proxy-core:34956]     (clinit):   4,669.41 ms
[info] [cloudstate-proxy-core:34956]     universe:   7,296.91 ms
[info] [cloudstate-proxy-core:34956]      (parse):   9,460.94 ms
[info] [cloudstate-proxy-core:34956]     (inline):  11,308.08 ms
[info] [cloudstate-proxy-core:34956]    (compile):  43,680.43 ms
[info] [cloudstate-proxy-core:34956]      compile:  68,467.83 ms
[info] [cloudstate-proxy-core:34956]        image:   5,779.23 ms
[info] [cloudstate-proxy-core:34956]        write:   1,930.98 ms
[info] [cloudstate-proxy-core:34956]      [total]: 296,677.26 ms
[success] Total time: 304 s, completed Aug 6, 2019 4:00:02 PM
```

The resulting Docker image is `cloudstate-proxy-dev-mode:latest`.

This image is a dev mode image, it uses an in memory journal, and forms a single cluster by itself. You can also run `dockerBuildNoJournal` and `dockerBuildInMemory`, `dockerBuildCassandra` to build a production proxy that has no journal, an in memory journal or Cassandra journal respectively. These will attempt the Kubernetes cluster bootstrap process, so can only be used in Kubernetes with appropriate environment variables set to help them discover other pods in the same deployment.

Substituting `publishLocal` for `publish` will push the docker images to a remote Docker registry, to enable this the `-Ddocker.registry` and `-Ddocker.username` flags must be specified to the `sbt` command.

## Running the docker image

The docker image can be run by running `docker run cloudstate-proxy-dev-mode`. However, by itself this won't be useful because the container won't be able to locate the user function from its container. If running the user function locally and your platform is Linux, then this can be enabled simply by passing the `--network=host` flag to use the hosts network namespace.

For all other platforms, the simplest way is to run the user function in a docker container, and share the network namespaces between the two containers. The `js-shopping-cart` sample app docker image can be build by running `npm run dockerbuild` from the `samples/js-shopping-cart` directory. Now, to start the images, this should be done in separate windows, or replace `-it` with `-d` to detach:

```bash
docker run -it --rm --name cloudstate -p 9000:9000 cloudstate-proxy-dev-mode
docker run -it --rm --network container:cloudstate --name shopping-cart -e "DEBUG=cloudstate*" js-shopping-cart
```

Initially the cloudstate container may show errors as it attempts to connect to the shopping-cart user function before it's started. Once running, you can connect to the proxy on port 9000.

## Building the Native Image outside of a container

If you wish to build a Native Image outside of a container, eg because you're using OSX, and you want better performance (since OSX runs Docker in a VM) or you want to run the image locally, then you can follow the following instructions.

### GraalVM Installation
Switch to GraalVM 19.1.1 as your current JRE, and add its binaries (in /bin) to $PATH. You *MUST* do this otherwise you'll get weird warnings since the GraalVM Substitution mechanism won't work.

Your `java -version` should report something like:

```bash
openjdk version "1.8.0_222"
OpenJDK Runtime Environment (build 1.8.0_222-20190711112007.graal.jdk8u-src-tar-gz-b08)
OpenJDK 64-Bit GraalVM CE 19.1.1 (build 25.222-b08-jvmci-19.1-b01, mixed mode)
```

Also, verify that you've added GraalVM correctly by checking that `native-image` is available as a command.

* Download and install GraalVM 19.1.1 CE
* set the GRAALVM_HOME and GRAALVM_VERSION ENV vars:
  Example for MacOS:
    export GRAALVM_VERSION=graalvm-ce-19.1.1
    export GRAALVM_HOME=<installation-parent-dir>/$GRAALVM_VERSION/Contents/Home

### Building

Switch to GraalVM 19.1.1 as your current JRE, and add its binaries (in /bin) to $PATH. You *MUST* do this otherwise you'll get weird warnings since the GraalVM Substitution mechanism won't work.

Your `java -version` should report something like:

```bash
openjdk version "1.8.0_222"
OpenJDK Runtime Environment (build 1.8.0_222-20190711112007.graal.jdk8u-src-tar-gz-b08)
OpenJDK 64-Bit GraalVM CE 19.1.1 (build 25.222-b08-jvmci-19.1-b01, mixed mode)
```

Also, verify that you've added GraalVM correctly by checking that `native-image` is available as a command.

Then either start creating the binary with the in-memory storage:

```bash
sbt "project proxy-core" "set graalVMVersion := None" graalvm-native-image:packageBin
```

or the Cassandra-client based storage binary:

```bash
sbt "project proxy-cassandra" "set graalVMVersion := None" graalvm-native-image:packageBin
```

The executable generated is located here:
*../cloudstate/proxy/core/target/graalvm-native-image/cloudstate-proxy-core*

### Running a generated executable

The binary will have to dynamically link to a *SunEC* provider, and needs to source it either from the present working dir, or via the **java.library.path**, this is achieved by passing in the following property when executing the binary:  *-Djava.library.path=<path-to-JRE>/lib*

Example: **-Djava.library.path=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/lib**

Supplying the runtime configuration, for the simplest experience, you can give it the pre-packaged dev-mode.conf, example: *-Dconfig.resource=dev-mode.conf*

Full example of running the in-memory storage executable: 

```bash
cloudstate/proxy/core/target/graalvm-native-image/./cloudstate-proxy-core -Djava.library.path=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/lib -Dconfig.resource=dev-mode.conf
```

Or with the Cassandra client storage:

```bash
cloudstate/proxy/cassandra/target/graalvm-native-image/./cloudstate-proxy-cassandra -Djava.library.path=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/lib
```
