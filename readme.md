# Raft and LSM Trees Implementation with Scala and Akka

A working project implementing the basics of the Raft consensus algorithm and Log-Structured Merge (LSM) trees using
Scala and Akka.

## Table of Contents

- [Introduction](#introduction)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
    - [Setup](#setup)
    - [Adding Data](#adding-data)
    - [Flushing Logs](#flushing-logs)
- [Limitations](#limitations)
- [Contributing](#contributing)
- [License](#license)

## Introduction

This project is an experimental implementation that combines the Raft consensus algorithm with Log-Structured Merge (
LSM) trees using Scala and Akka. It serves as a proof of concept to demonstrate that Raft can be built using Akka in a
Scala application.

## Features

- Basic implementation of the Raft consensus algorithm.
- Data storage using Log-Structured Merge (LSM) trees.
- RESTful API endpoints for interacting with the system.
- Data persistence to disk via log flushing.
- Built with Scala and Akka for concurrency and distributed computing.

## Prerequisites

- **Scala** (version compatible with your project)
- **SBT** (Scala Build Tool)
- **Java JDK** (version 8 or higher)
- **cURL** (for testing API endpoints)

## Getting Started

### Setup

1. **Clone the repository**:

   ```bash
   git clone https://github.com/yourusername/yourprojectname.git
   cd yourprojectname
   ```

2. **Build and run the application**:

   ```bash
   sbt run
   ```

3. **Initialize the setup**:

   ```bash
   curl -X POST http://localhost:9000/setup
   ```

### Adding Data

Add data to the system using the following `curl` commands:

```bash
curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a6",
    "value": "a"
}'

curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a61",
    "value": "a"
}'

curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a62",
    "value": "a"
}'

curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a63",
    "value": "a"
}'

curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a64",
    "value": "a"
}'

curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a65",
    "value": "a"
}'

curl --location 'http://localhost:9000/members/addLogs' \
--header 'Content-Type: application/json' \
--data '{
    "key": "a66",
    "value": "a"
}'
```

### Flushing Logs

Persist logs to disk and publish data to internal data structures:

```bash
curl -X POST http://localhost:9000/members/flushLogs
```

## Limitations

- **Experimental Implementation**: The correctness of the implementation is still under evaluation. As with other
  distributed systems, achieving consistency and fault tolerance is complex.
- **Not Production-Ready**: This project is intended for educational and experimental purposes.

## Contributing

Contributions are welcome! Please open an issue or submit a pull request for any improvements or suggestions.

## License

This project is licensed under the [MIT License](LICENSE).

---

*Note: This project is an experiment to demonstrate that Raft can be built using Akka in a Scala application.*

developer: @pankajbhardwaj