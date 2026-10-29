package com.datum.clientmanagement;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads broker state with {@code rabbitmqctl} inside the RabbitMQ test container (what AMQP itself cannot show). */
public final class RabbitCtl {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RabbitCtl() {
    }

    /** One JSON object per row, e.g. {@code list(container, "list_queues", "name", "durable")}. */
    public static List<JsonNode> list(RabbitMQContainer container, String command, String... columns) {
        List<String> arguments = new ArrayList<>(List.of("rabbitmqctl", "--quiet", "--formatter", "json", command));
        arguments.addAll(List.of(columns));
        ExecResult result;
        try {
            result = container.execInContainer(arguments.toArray(String[]::new));
        } catch (Exception exception) {
            throw new IllegalStateException("rabbitmqctl " + command + " could not run", exception);
        }
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return StreamSupport.stream(JSON.readTree(result.getStdout()).spliterator(), false).toList();
    }

    /**
     * Messages in the queue that are ready or delivered but not yet acked. Zero means every delivery was acked,
     * which a passive queue declare (ready messages only) cannot tell.
     */
    public static long messageCount(RabbitMQContainer container, String queue) {
        return list(container, "list_queues", "name", "messages").stream()
                .filter(row -> queue.equals(row.path("name").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("queue " + queue + " does not exist"))
                .path("messages").asLong();
    }
}
