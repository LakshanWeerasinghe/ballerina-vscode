import ballerina/mqtt;
import ballerinax/rabbitmq;
import ballerinax/salesforce;
import ballerinax/solace;
import ballerinax/solace.jms;

listener mqtt:Listener mqttListener = new (mqtt:DEFAULT_URL, "unique_client_001", "topic1");
listener rabbitmq:Listener orderListener = new (rabbitmq:DEFAULT_HOST, 5671);
listener rabbitmq:Listener deliveryListener = new (rabbitmq:DEFAULT_HOST, 5671);
listener solace:Listener solaceListener = new ("smf://localhost:55554", messageVpn = "default");
listener salesforce:Listener salesforceListener = new ({auth: {username: "abcd", password: "xxxx"}});
listener jms:Listener solaceJmsListener = new ("smf://localhost:55555", messageVpn = "default");
