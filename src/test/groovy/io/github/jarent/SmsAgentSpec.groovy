package io.github.jarent

import groovy.json.JsonSlurper
import io.github.jarent.munit.MunitSpecification

import java.text.BreakIterator

import org.mule.api.MuleEvent
import org.mule.api.MuleMessage
import org.mule.api.client.OperationOptions
import org.mule.module.http.api.client.HttpRequestOptionsBuilder
import org.mule.munit.common.mocking.SpyProcess

class SmsAgentSpec extends MunitSpecification  {
	
	@Override
	protected String getConfigResources() {
			return ["sms-agent.xml"
					].join(",")
	}
	
	@Override
	protected boolean haveToDisableInboundEndpoints() {
		return false;
	}

	@Override
	protected boolean haveToMockMuleConnectors() {
		return false;
	}
	
	
	def "should respond for query"() {
		
		given: "Request with sms text and fromNumber"		
		String request = '''
		{  
		   "sms":{  
		      "text":"Do you still have dryer ?",
		      "fromNumber":1234567890
		   },
           "apiAiToken":"xxxxxxxxxxxxxxxxxxxxxxxxxxx",
           "iftttMakerTrigger":{
                "eventName":"smsAgentResponse",
                "key":"kkkkkkkkkkkkkkkkkkkkkkkk"
           }
		}		
		'''
		
		when: "sent"
		MuleMessage result = send request
		
		then: "success status returned"		
		result.getInboundProperty('http.status') == 200
		
		and: "sms response received from bot"
		result.getPayloadAsString() != null
	}
	
	def "should trigger IFTTT Maker notification"() {
		
		given: "Request with sms text"
		String request = '''
		{  
		   "sms":{  
		      "text":"Do you still have dryer?",
		      "fromNumber":1234567890
		   },
           "apiAiToken":"xxxxxxxxxxxxxxxxxxxxxxx",
           "iftttMakerTrigger":{
                "eventName":"smsAgentResponse",
                "key":"kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk"
           }
		}		
		'''
		
		def payloadBeforeIFTTT = null;
		
		spyMessageProcessor("request").ofNamespace("http").
			withAttributes(['doc:name': 'Trigger IFTTT Notification']).before(
				[spy: {MuleEvent ev -> 
					payloadBeforeIFTTT = new JsonSlurper().parseText(ev.getMessage().getPayloadAsString())
					}
				] as SpyProcess
				)
		
		when: "sent"
		MuleMessage result = send request
		
		then: "success status returned"
		result.getInboundProperty('http.status') == 200
		
		and: "IFTTT Maker was called once"
		verifyCallOfMessageProcessor("request").ofNamespace("http").withAttributes(['doc:name': 'Trigger IFTTT Notification']).times(1)
		
		System.err.println(payloadBeforeIFTTT)
		and: "value1 is populated with answer"
		payloadBeforeIFTTT.value1 == new JsonSlurper().parseText(result.getPayloadAsString()).answer;
		
		and: "value2 is populated with fromNumber"
		payloadBeforeIFTTT.value2 == "1234567890"
	}
	

	def "should split multiline text"() {
		given: "Request with 2 sentences and unique sessionId (phone Number)"
		
		long randomPhoneNumber = new Random().nextLong()
		
		def requestBuilder = new groovy.json.JsonBuilder()
		requestBuilder {
			    iftttMakerTrigger {
					eventName "smsAgentResponse"
					key "kkkkkkkkkkkkkkkkkkkk"
				}
			    apiAiToken "xxxxxxxxxxxxxxxxxxxxxx"
				sms {
					text "Do you still have dryer ? I will take it for 20 bucks"
					fromNumber randomPhoneNumber
				}
		}		
		
		def payloadBeforeApiAi = null;
		
		spyMessageProcessor("request").ofNamespace("http").
			withAttributes(['doc:name': 'query api.ai']).before(
				[spy: {MuleEvent ev ->
					payloadBeforeApiAi = new JsonSlurper().parseText(ev.getMessage().getPayloadAsString())
					}
				] as SpyProcess
				)
		
		when: "request sent"
		MuleMessage result = send requestBuilder.toString()
		
		then: "success status returned"
		result.getInboundProperty('http.status') == 200
		
		and: "api.ai was called twice"
		verifyCallOfMessageProcessor("request").ofNamespace("http").withAttributes(['doc:name': 'query api.ai']).times(2)
		
		and: "last answer is returned"
		def jsonResponse = new JsonSlurper().parseText(result.getPayloadAsString())	
		jsonResponse.answer == "Let me think about your offer."
		
		and: "fromNumber was used as sessionId"
		payloadBeforeApiAi.sessionId == randomPhoneNumber
		
	}
	
	private MuleMessage send(String requestBody) {
		MuleMessage request = testEvent(requestBody).getMessage().with {
			it.setOutboundProperty('Content-Type','application/json')
			return it
		}
		
		OperationOptions options = HttpRequestOptionsBuilder.newOptions()
		.method('post')
		.disableStatusCodeValidation()
		.responseTimeout(10000)
		.build()

		//mock ifttt maker
		whenMessageProcessor("request").ofNamespace("http").
		withAttributes(['doc:name': 'Trigger IFTTT Notification']).thenReturnSameEvent()
		
		//mock api ai
		whenMessageProcessor("request").ofNamespace("http").
		withAttributes(['doc:name': 'query api.ai']).thenReturn(
			muleMessageWithPayload('''{ "result": {
					    "fulfillment": {
					      "speech": "Let me think about your offer."
					       }
						}
					}
			''')
			)
											
		return muleContext.getClient().send('http://localhost:8080/api/smsagent/query', request, options)
	}

}
