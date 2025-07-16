// page.tsx

"use client"


import type React from "react"

import { useState, useEffect, useRef } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { Upload, Send, Users, Download } from "lucide-react"
import { useToast } from "@/hooks/use-toast"

interface Message {
  sender: string
  seq: number
  message: string
  type: "text" | "file_chunk"
  timestamp: number
  fileName?: string
  chunkIndex?: number
  totalChunks?: number
}

interface ClientState {
  nextSeqNum: number
  baseSeq: number
  windowSize: number
  sendBuffer: Map<number, { message: string; timestamp: number; retryCount: number }>
  lastReceivedSeq: number
  timeouts: Map<number, NodeJS.Timeout>
}

const WINDOW_SIZE = 5
const TIMEOUT_MS = 3000
const MAX_RETRIES = 3
const POLLING_INTERVAL = 2000
const SERVER_URL = "http://localhost:8080"

export default function ReliableChatApp() {
  const [username, setUsername] = useState("")
  const [receiver, setReceiver] = useState("")
  const [message, setMessage] = useState("")
  const [messages, setMessages] = useState<Message[]>([])
  const [isConnected, setIsConnected] = useState(false)
  const [activeUsers, setActiveUsers] = useState<string[]>([])
  const [clientState, setClientState] = useState<ClientState>({
    nextSeqNum: 0,
    baseSeq: 0,
    windowSize: WINDOW_SIZE,
    sendBuffer: new Map(),
    lastReceivedSeq: -1,
    timeouts: new Map(),
  })
  const [fileChunks, setFileChunks] = useState<Map<string, string[]>>(new Map())

  const { toast } = useToast()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const pollingRef = useRef<NodeJS.Timeout>()

  useEffect(() => {
    if (isConnected) {
      startPolling()
      fetchActiveUsers()
    }

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current)
      }
      // Clear all timeouts
      clientState.timeouts.forEach((timeout) => clearTimeout(timeout))
    }
  }, [isConnected])

  const startPolling = () => {
    pollingRef.current = setInterval(async () => {
      try {
        const response = await fetch(`${SERVER_URL}/receive?user=${username}&lastAck=${clientState.lastReceivedSeq}`)
        const data = await response.json()

        if (data.messages && data.messages.length > 0) {
          setMessages((prev) => [...prev, ...data.messages])

          // Process file chunks
          data.messages.forEach((msg: Message) => {
            if (msg.type === "file_chunk" && msg.fileName) {
              setFileChunks((prev) => {
                const newChunks = new Map(prev)
                const key = `${msg.fileName}_${msg.sender}`
                const chunks = newChunks.get(key) || []
                chunks[msg.chunkIndex!] = msg.message
                newChunks.set(key, chunks)

                // Check if file is complete
                if (chunks.filter(Boolean).length === msg.totalChunks) {
                  toast({
                    title: "File received",
                    description: `${msg.fileName} from ${msg.sender}`,
                  })
                }

                return newChunks
              })
            }
          })

          setClientState((prev) => ({
            ...prev,
            lastReceivedSeq: Math.max(prev.lastReceivedSeq, ...data.messages.map((m: Message) => m.seq)),
          }))
        }

        // Handle ACKs
        if (data.ack !== undefined) {
          handleAck(data.ack)
        }
      } catch (error) {
        console.error("Polling error:", error)
      }
    }, POLLING_INTERVAL)
  }

  const handleAck = (ack: number) => {
    setClientState((prev) => {
      const newState = { ...prev }

      // Update base sequence
      if (ack >= newState.baseSeq) {
        // Clear acknowledged messages from buffer
        for (let seq = newState.baseSeq; seq <= ack; seq++) {
          newState.sendBuffer.delete(seq)
          if (newState.timeouts.has(seq)) {
            clearTimeout(newState.timeouts.get(seq)!)
            newState.timeouts.delete(seq)
          }
        }
        newState.baseSeq = ack + 1
      }

      return newState
    })
  }

  const sendMessage = async (msg: string, type: "text" | "file_chunk" = "text", fileData?: any) => {
    if (clientState.nextSeqNum >= clientState.baseSeq + clientState.windowSize) {
      toast({
        title: "Window full",
        description: "Please wait for acknowledgments",
        variant: "destructive",
      })
      return
    }

    const seq = clientState.nextSeqNum
    const payload = {
      sender: username,
      receiver: receiver,
      seq: seq,
      message: msg,
      type: type,
      ...fileData,
    }

    try {
      const response = await fetch(`${SERVER_URL}/send-message`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      })

      if (response.ok) {
        const data = await response.json()

        // Store in send buffer
        setClientState((prev) => {
          const newState = { ...prev }
          newState.sendBuffer.set(seq, { message: msg, timestamp: Date.now(), retryCount: 0 })
          newState.nextSeqNum++

          // Set timeout for retransmission
          const timeout = setTimeout(() => retransmit(seq), TIMEOUT_MS)
          newState.timeouts.set(seq, timeout)

          return newState
        })

        if (data.ack !== undefined) {
          handleAck(data.ack)
        }
      }
    } catch (error) {
      console.error("Send error:", error)
      toast({
        title: "Send failed",
        description: "Message could not be sent",
        variant: "destructive",
      })
    }
  }

  const retransmit = async (seq: number) => {
    const bufferEntry = clientState.sendBuffer.get(seq)
    if (!bufferEntry || bufferEntry.retryCount >= MAX_RETRIES) {
      toast({
        title: "Message failed",
        description: `Message with seq ${seq} failed after ${MAX_RETRIES} retries`,
        variant: "destructive",
      })
      return
    }

    console.log(`Retransmitting message seq: ${seq}`)

    try {
      const response = await fetch(`${SERVER_URL}/send-message`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          sender: username,
          receiver: receiver,
          seq: seq,
          message: bufferEntry.message,
        }),
      })

      if (response.ok) {
        const data = await response.json()

        // Update retry count
        setClientState((prev) => {
          const newState = { ...prev }
          const entry = newState.sendBuffer.get(seq)
          if (entry) {
            entry.retryCount++
            newState.sendBuffer.set(seq, entry)
          }

          // Set new timeout
          const timeout = setTimeout(() => retransmit(seq), TIMEOUT_MS)
          if (newState.timeouts.has(seq)) {
            clearTimeout(newState.timeouts.get(seq)!)
          }
          newState.timeouts.set(seq, timeout)

          return newState
        })

        if (data.ack !== undefined) {
          handleAck(data.ack)
        }
      }
    } catch (error) {
      console.error("Retransmit error:", error)
    }
  }

  const handleSendMessage = () => {
    if (message.trim() && receiver) {
      sendMessage(message.trim())
      setMessage("")
    }
  }

  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file || !receiver) return

    const CHUNK_SIZE = 1024 * 64 // 64KB chunks
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE)

    toast({
      title: "Uploading file",
      description: `Sending ${file.name} in ${totalChunks} chunks`,
    })

    for (let i = 0; i < totalChunks; i++) {
      const start = i * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, file.size)
      const chunk = file.slice(start, end)

      const reader = new FileReader()
      reader.onload = async () => {
        const chunkData = btoa(reader.result as string)

        try {
          const response = await fetch(`${SERVER_URL}/upload-chunk`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              sender: username,
              receiver: receiver,
              fileName: file.name,
              chunkIndex: i,
              totalChunks: totalChunks,
              chunkData: chunkData,
              seq: clientState.nextSeqNum + i,
            }),
          })

          if (response.ok) {
            console.log(`Chunk ${i + 1}/${totalChunks} uploaded`)
          }
        } catch (error) {
          console.error("Chunk upload error:", error)
        }
      }

      reader.readAsBinaryString(chunk)
    }
  }

  const downloadFile = async (fileName: string, sender: string) => {
    try {
      const response = await fetch(`${SERVER_URL}/download-file?fileName=${fileName}&receiver=${username}`)
      const data = await response.json()

      if (data.chunks && data.chunks.length > 0) {
        const binaryString = data.chunks.map((chunk: string) => atob(chunk)).join("")
        const bytes = new Uint8Array(binaryString.length)
        for (let i = 0; i < binaryString.length; i++) {
          bytes[i] = binaryString.charCodeAt(i)
        }

        const blob = new Blob([bytes])
        const url = URL.createObjectURL(blob)
        const a = document.createElement("a")
        a.href = url
        a.download = fileName
        a.click()
        URL.revokeObjectURL(url)

        toast({
          title: "File downloaded",
          description: fileName,
        })
      }
    } catch (error) {
      console.error("Download error:", error)
      toast({
        title: "Download failed",
        description: "Could not download file",
        variant: "destructive",
      })
    }
  }

  const fetchActiveUsers = async () => {
    try {
      const response = await fetch(`${SERVER_URL}/users`)
      const users = await response.json()
      setActiveUsers(users.filter((user: string) => user !== username))
    } catch (error) {
      console.error("Failed to fetch users:", error)
    }
  }

  const connect = () => {
    if (username.trim()) {
      setIsConnected(true)
      toast({
        title: "Connected",
        description: `Connected as ${username}`,
      })
    }
  }

  if (!isConnected) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>Reliable Chat System</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <Input
              placeholder="Enter your username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              onKeyPress={(e) => e.key === "Enter" && connect()}
            />
            <Button onClick={connect} className="w-full">
              Connect
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="flex h-screen bg-gray-100">
      {/* Sidebar */}
      <div className="w-64 bg-white border-r">
        <div className="p-4 border-b">
          <h2 className="font-semibold flex items-center gap-2">
            <Users className="h-4 w-4" />
            Active Users
          </h2>
        </div>
        <div className="p-2">
          {activeUsers.map((user) => (
            <Button
              key={user}
              variant={receiver === user ? "default" : "ghost"}
              className="w-full justify-start mb-1"
              onClick={() => setReceiver(user)}
            >
              {user}
            </Button>
          ))}
        </div>
      </div>

      {/* Main Chat Area */}
      <div className="flex-1 flex flex-col">
        <div className="p-4 bg-white border-b">
          <div className="flex items-center justify-between">
            <h1 className="text-xl font-semibold">{receiver ? `Chat with ${receiver}` : "Select a user to chat"}</h1>
            <div className="flex items-center gap-2">
              <Badge variant="outline">
                Window: {clientState.nextSeqNum - clientState.baseSeq}/{clientState.windowSize}
              </Badge>
              <Badge variant="outline">Seq: {clientState.nextSeqNum}</Badge>
            </div>
          </div>
        </div>

        <ScrollArea className="flex-1 p-4">
          <div className="space-y-4">
            {messages.map((msg, index) => (
              <div key={index} className={`flex ${msg.sender === username ? "justify-end" : "justify-start"}`}>
                <div
                  className={`max-w-xs lg:max-w-md px-4 py-2 rounded-lg ${
                    msg.sender === username ? "bg-blue-500 text-white" : "bg-gray-200 text-gray-800"
                  }`}
                >
                  <div className="text-xs opacity-70 mb-1">
                    {msg.sender} (seq: {msg.seq})
                  </div>
                  {msg.type === "file_chunk" && msg.fileName ? (
                    <div className="flex items-center gap-2">
                      <span>📎 {msg.fileName}</span>
                      {msg.chunkIndex === msg.totalChunks! - 1 && (
                        <Button size="sm" variant="outline" onClick={() => downloadFile(msg.fileName!, msg.sender)}>
                          <Download className="h-3 w-3" />
                        </Button>
                      )}
                    </div>
                  ) : (
                    <div>{msg.message}</div>
                  )}
                  <div className="text-xs opacity-50 mt-1">{new Date(msg.timestamp).toLocaleTimeString()}</div>
                </div>
              </div>
            ))}
          </div>
        </ScrollArea>

        {receiver && (
          <div className="p-4 bg-white border-t">
            <div className="flex gap-2">
              <Input
                placeholder="Type your message..."
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                onKeyPress={(e) => e.key === "Enter" && handleSendMessage()}
                className="flex-1"
              />
              <input type="file" ref={fileInputRef} onChange={handleFileUpload} className="hidden" />
              <Button variant="outline" onClick={() => fileInputRef.current?.click()}>
                <Upload className="h-4 w-4" />
              </Button>
              <Button onClick={handleSendMessage}>
                <Send className="h-4 w-4" />
              </Button>
            </div>
            <div className="text-xs text-gray-500 mt-2">
              Buffer size: {clientState.sendBuffer.size} | Pending ACKs: {clientState.nextSeqNum - clientState.baseSeq}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
