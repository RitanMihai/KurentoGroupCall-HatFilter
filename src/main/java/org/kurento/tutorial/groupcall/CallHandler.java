/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.groupcall;

import java.io.IOException;
import java.util.Map;

import com.google.gson.JsonPrimitive;
import org.kurento.client.FaceOverlayFilter;
import org.kurento.client.IceCandidate;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * 
 * @author Ivan Gracia (izanmail@gmail.com)
 * @since 4.3.1
 */
public class CallHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CallHandler.class);

  private static final Gson gson = new GsonBuilder().create();

  @Autowired
  private RoomManager roomManager;

  @Autowired
  private UserRegistry registry;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    final JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

    final UserSession user = registry.getBySession(session);

    if (user != null) {
      log.debug("Incoming message from user '{}': {}", user.getName(), jsonMessage);
    } else {
      log.debug("Incoming message from new user: {}", jsonMessage);
    }

    switch (jsonMessage.get("id").getAsString()) {
      case "joinRoom":
        joinRoom(jsonMessage, session);
        break;
      case "receiveVideoFrom":
        final String senderName = jsonMessage.get("sender").getAsString();
        final UserSession sender = registry.getByName(senderName);
        final String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
        user.receiveVideoFrom(sender, sdpOffer);
        break;
      case "leaveRoom":
        leaveRoom(user);
        break;
      case "onIceCandidate":
        JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

        if (user != null) {
          IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
              candidate.get("sdpMid").getAsString(), candidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(cand, jsonMessage.get("name").getAsString());
        }
        break;
      case "startFilter":
        String appServerUrl = "https://raw.githubusercontent.com/Kurento/test-files/main";
        String imageURL = appServerUrl + "/img/mario-wings.png";
        applyFaceOverlayFilter(session, imageURL);
        break;

      case "chat":
        final String id = "receiveTextAnswer";
        final String textMessageSender = jsonMessage.get("sender").getAsString();
        final String textMessageContent = jsonMessage.get("content").getAsString();

        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        response.addProperty("sender", textMessageSender);
        response.addProperty("content", textMessageContent);

        registry.getUsersByName().entrySet().forEach(stringUserSessionEntry -> {
          try {
            UserSession currentUserSession = stringUserSessionEntry.getValue();
            boolean isInTheSameRoom = user.getRoomName().equals(currentUserSession.getRoomName());
            if(isInTheSameRoom)
              stringUserSessionEntry.getValue().sendMessage(response);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });

        break;
      default:
        break;
    }
  }

  /***
   * @param session of the current user
   * @param imageUrl image that is placed over the head
   * @throws IOException
   */
  public void applyFaceOverlayFilter(WebSocketSession session, String imageUrl) throws IOException {
    UserSession userSession = registry.getBySession(session);
    if (userSession != null) {
      MediaPipeline pipeline = userSession.getPipeline();

      // Create and configure the face overlay filter
      FaceOverlayFilter filter = new FaceOverlayFilter.Builder(pipeline).build();
      filter.setOverlayedImage(imageUrl, -0.35F, -1.2F, 1.6F, 1.6F);

        /* https://doc-kurento.readthedocs.io/en/latest/tutorials/java/tutorial-magicmirror.html */
        /* Current stream, of the user agent that called the function is attached to the filter */
        userSession.getOutgoingWebRtcPeer().connect(filter);
        for (Map.Entry<String, UserSession> stringUserSessionEntry : registry.getUsersByName().entrySet()) {
          /* Access the others participants incoming session and attach to them the filter */
          if(!stringUserSessionEntry.getKey().equals(userSession.getName())){
            var sessionRemote = stringUserSessionEntry.getValue().getIncomingMedia().get(userSession.getName());
            filter.connect(sessionRemote);
          }
          else {
            /* An approach to get the filter on the local side too, this does not work.
               I think of two approaches:
               - Use the javascript kurento library on the web part;
               - Change the video container, on the web part, with a remote container, of the same stream processed;
             */
            var localSession = stringUserSessionEntry.getValue().getOutgoingWebRtcPeer();
            filter.connect(localSession);
          }
      }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    UserSession user = registry.removeBySession(session);
    roomManager.getRoom(user.getRoomName()).leave(user);
  }

  private void joinRoom(JsonObject params, WebSocketSession session) throws IOException {
    final String roomName = params.get("room").getAsString();
    final String name = params.get("name").getAsString();
    log.info("PARTICIPANT {}: trying to join room {}", name, roomName);

    Room room = roomManager.getRoom(roomName);
    final UserSession user = room.join(name, session);
    registry.register(user);
  }

  private void leaveRoom(UserSession user) throws IOException {
    final Room room = roomManager.getRoom(user.getRoomName());
    room.leave(user);
    if (room.getParticipants().isEmpty()) {
      roomManager.removeRoom(room);
    }
  }
}
