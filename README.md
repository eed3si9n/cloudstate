# CloudState

## Scalable Compute needs Scalable State

Bringing stateful microservices, and the power of reactive technologies to the Cloud Native ecosystem breaks down the final impediment standing in the way of a Serverless platform for general-purpose application development, true elastic scalability, and global deployment in the Kubernetes ecosystem. The marriage of Knative and Akka Cluster on Kubernetes allows applications to not only scale efficiently, but to manage distributed state reliably at scale while maintaining its global or local level of data consistency, opening up for a whole range of new addressable use-cases.

## Status

We are actively working on a Proof of Concept for CloudState event sourcing. This document describes the PoC, how to run, test and develop it. This document will likely change frequently as the PoC progresses.

Currently, we have the Akka CloudState event sourcing proxy component implemented and a JavaScript user function. We also have a Kubernetes operator that facilitates the deployment of these functions, with the Akka backend injected, using Cassandra as a persistent store for the journal.

The operator should work on any Kubernetes distribution, we have tested on GKE with Kubernetes 1.11 and 1.12.

### Running on GKE

1. Create a GKE cluster. We recommend at least 6 vCPUs (ie, a node pool of 3 `n1-standard-2` nodes). Also ensure that the current user is a cluster admin. Detailed instructions for creating the GKE cluster can be found in the [Knative documentation](https://github.com/knative/docs/blob/master/docs/install/Knative-with-GKE.md), follow all the steps up to (but not including) installing Knative.
2. If using an event sourced entity, install Cassandra. This can be done from the Google Marketplace, by visiting the [Cassandra Cluster](https://console.cloud.google.com/marketplace/details/google/cassandra), selecting configure, selecting your GCloud project, and then installing it in the Kubernetes cluster you just created. The defaults should be good enough, in our examples we called the app instance name `cassandra`. Note there is an option to use an in memory journal if you just want to test it out, of course, as soon as your pods shut down (or if they are rebalanced), your journals will be lost.
3. Install the CloudState operator:

    ```
    kubectl apply -f https://raw.githubusercontent.com/cloudstateio/cloudstate/master/operator/stateful-serverless.yaml
    ```
    
You are now ready to install an event sourced function. We have a shopping cart example in the `samples/js-shopping-cart` directory of this project. This can be installed by following these instructions:

1. Configure a Cassandra journal. If you called your Cassandra deployment `cassandra` and deployed it to the default namespace, this can be installed by running:

    ```
    kubectl apply -f https://raw.githubusercontent.com/cloudstateio/cloudstate/master/samples/js-shopping-cart/cassandra-journal.yaml
    ```
    
    Otherwise, download the above file and update the `service` parameter to match the first node of your Cassandra stateful set.
    
2. Install the shopping cart, this can be done by running:

    ```
    kubectl apply -f https://raw.githubusercontent.com/cloudstateio/cloudstate/master/samples/js-shopping-cart/js-shopping-cart.yaml
    ```
    
The operator will install a service, you can then create an ingress for that service. To test, instantiate a gRPC client in your favourite language for [this descriptor](https://raw.githubusercontent.com/cloudstateio/cloudstate/master/examples/shoppingcart/shoppingcart.proto). You may need to also download the [`cloudstate/entitykey.proto`](https://raw.githubusercontent.com/cloudstateio/cloudstate/master/protols/frontend/cloudstate/entity.proto) and [`google/protobuf/empty.proto`](https://raw.githubusercontent.com/protocolbuffers/protobuf/master/src/google/protobuf/empty.proto) descriptors to compile it in your language. The shopping cart descriptor is deployed with debug on, so try getting the logs of the `shopping-cart` container in each of the deployed pods to see what's happening when commands are sent.

### Points of interest

We'll start with the user function, which can be found in [`samples/js-shopping-cart`](samples/js-shopping-cart). The following files are interesting:

* [`shoppingcart.proto`](protocols/example/shoppingcart/shoppingcart.proto) - This is the gRPC interface that is exposed to the rest of the world. The user function doesn't implement this directly, it passes it to the Akka backend, that implements it, and then proxies all requests to the user function through an event sourcing specific protocol. Note the use of the `cloudstate.entity_key` field option extension, this is used to indicate which field(s) form the entity key, which the Akka backend will use to identify entities and shard them.
* [`domain.proto`](protocols/example/shoppingcart/persistence/domain.proto) - These are the protobuf message definitions for the domain events and state. They are used to serialize and deserialize events stored in the journal, as well as being used to store the current state which gets serialized and deserialized as snapshots when snapshotting is used.
* [`shoppingcart.js`](samples/js-shopping-cart/shoppingcart.js) - This is the JavaScript code for implementing the shopping cart entity user function. It defines handlers for events and commands. It uses the `cloudstate-event-sourcing` Node.js module to actually implement the event sourcing protocol.

Onto the `cloudstate-event-sourcing` Node module, which can be found in [`node-support`](node-support). While there's no reason why the user function couldn't implement the event sourcing protocol directly, it is a little low level. This library provides an idiomatic JavaScript API binding to the protocol. It is expected that such a library would be provided for all support languages.

* [`entity.proto`](protocols/frontend/cloudstate/entity.proto) - This is the protocol that is implemented by the library, and invoked by the Akka backend. Commands, replies, events and snapshots are serialized into `google.protobuf.Any` - the command payloads and reply payloads are the gRPC input and output messages, while the event and snapshot payloads are what gets stored to persistence. The `ready` rpc method on the `Entity` service is used by the Akka backend to ask the user function for the gRPC protobuf descriptor it wishes to be served, this uses `google.protobuf.FileDescriptorProto` to serialize the descriptor.
* [`entity.js`](node-support/src/entity.js) - This is the implementation of the protocol, which adapts the protocol to the API used by the user function.

Next we'll take a look at the Akka proxy, which can be found in [`proxy/core`](proxy/core).

* [`Serve.scala`](proxy/core/src/main/scala/io/cloudstate/proxy/Serve.scala) - This provides the dynamically implemented gRPC interface as specified by the user function. Requests are forwarded as commands to the cluster sharded persistent entities.
* [`StateManager.scala`](proxy/core/src/main/scala/io/cloudstate/proxy/StateManager.scala) - This is an Akka persistent actor that talks to the user function via the event sourcing gRPC protocol.
* [`CloudStateProxyMain.scala`](proxy/core/src/main/scala/io/cloudstate/proxy/CloudStateProxyMain.scala) - This pulls everything together, starting the Akka gRPC server, cluster sharding, and persistence.
* [`HttpApi.scala`](proxy/core/src/main/scala/io/cloudstate/proxy/HttpApi.scala) - This reads [google.api.HttpRule](src/protocols/frontend/google/api/http.proto) annotations to generate HTTP/1.1 + JSON endpoints for the gRPC service methods.

## Compliance testing

The [TCK](tck/src/test/resources/application.conf) makes it possible to verify that combinations of backends and frontends behaves as expected. In order to make a frontend eligible for testing in the TCK a sample application implementing a simple [Shopping Cart](samples/js-shopping-cart) (here showcased with the Node.js frontend) is required.

## GraalVM Native Image generation

The Akka-based Proxy Sidecar Reference Implementation can be generated into a native executable via GraalVM `native-image`, instructions for doing so is available [here](GRAALVM.md).

## Docs
- See the [rationale](RATIONALE.md) document for a more context.
- Read the current [documentation](docs/README.md) and [spec](SPEC.md). 
