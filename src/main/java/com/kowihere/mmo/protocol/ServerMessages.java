package com.kowihere.mmo.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Everything the server sends. Two shapes only: a full {@code init} that
 * describes the world from scratch, and a {@code delta} carrying just what
 * changed since version {@code v - 1}.
 *
 * <p>Empty collections are omitted from the wire ({@link JsonInclude}), which
 * keeps an idle tick's delta down to a few dozen bytes.
 */
public final class ServerMessages {

    private ServerMessages() {
    }

    public record ActorDto(int id, String name, int x, int y, String dir, boolean online) {
    }

    /** One step in progress: the actor left ({@code fx},{@code fy}) and arrives at ({@code x},{@code y}) in {@code ms}. */
    public record MoveDto(int id, int fx, int fy, int x, int y, String dir, int ms) {
    }

    public record ChatDto(int id, String name, String text) {
    }

    public record PresenceDto(int id, boolean online) {
    }

    public record MapDto(String id, String name, int width, int height, int tileSize, List<String> collision) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Init(String type, long v, MapDto map, int selfId, List<ActorDto> actors) {
        public Init(long v, MapDto map, int selfId, List<ActorDto> actors) {
            this("init", v, map, selfId, actors);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Delta(String type, long v, List<ActorDto> joined, List<Integer> left,
                        List<MoveDto> moved, List<ChatDto> chat, List<PresenceDto> presence) {
        public Delta(long v, List<ActorDto> joined, List<Integer> left,
                     List<MoveDto> moved, List<ChatDto> chat, List<PresenceDto> presence) {
            this("delta", v, joined, left, moved, chat, presence);
        }
    }

    public record Error(String type, String message) {
        public Error(String message) {
            this("error", message);
        }
    }
}
