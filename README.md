# Tail queue

Allows to write and send lines from a simple file using tail-like mechanism

## Features

* no dependencies
* small footprint
* simplest implementation which work without any side effects

## How to add it into your app

### Maven

```xml
<repositories>
    <repository>
        <id>pne</id>
        <name>payneteasy repo</name>
        <url>https://maven.pne.io</url>
    </repository>
</repositories>
  
<dependency>
    <groupId>com.payneteasy.tail-queue</groupId>
    <artifactId>tail-queue-core</artifactId>
    <version>SEE RELEASES</version>
</dependency>
```

## Example

```java
ITailQueue queue = new TailQueueBuilder()
    .dir(new File("./queue-dir"))
    .sender(aLine -> LOG.info("Sending line {}", aLine))
    .build();

queue.startQueueSender();

ITailQueueWriter writer = queue.getWriter();

writer.writeMessage("Hello " + i);

...

queue.shutdownQueueSender();
```
