package com.thingworx.things.agent.llm;

public class ToolCall {

    private final String id;
    private final String functionName;
    private final String arguments;

    public ToolCall(String id, String functionName, String arguments) {
        this.id = id;
        this.functionName = functionName;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public String getFunctionName() { return functionName; }
    public String getArguments() { return arguments; }

    @Override
    public String toString() {
        return "ToolCall{name='" + functionName + "', id='" + id + "'}";
    }
}
