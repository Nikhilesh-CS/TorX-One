import SwiftUI

struct ChatListView: View {
    @EnvironmentObject var viewModel: ChatViewModel
    @State private var showingAddContact = false
    @State private var showingMyKey = false
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(hue: 0.61, saturation: 0.25, brightness: 0.08)
                    .ignoresSafeArea()
                
                if viewModel.contacts.isEmpty {
                    VStack(spacing: 20) {
                        Image(systemName: "person.2.slash")
                            .font(.system(size: 48))
                            .foregroundColor(.gray)
                        Text("No Contacts Yet")
                            .font(.headline)
                            .foregroundColor(.gray)
                        Text("Share your key or add someone else's to start chatting.")
                            .multilineTextAlignment(.center)
                            .foregroundColor(.gray.opacity(0.8))
                            .padding(.horizontal, 40)
                    }
                } else {
                    List {
                        ForEach(viewModel.contacts) { contact in
                            NavigationLink(destination: ChatView(contact: contact)) {
                                HStack {
                                    Circle()
                                        .fill(LinearGradient(
                                            colors: [Color(hue: 0.55, saturation: 0.8, brightness: 0.9),
                                                     Color(hue: 0.75, saturation: 0.7, brightness: 0.95)],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                        ))
                                        .frame(width: 40, height: 40)
                                        .overlay(
                                            Text(String(contact.name.prefix(1)).uppercased())
                                                .foregroundColor(.white)
                                                .font(.headline)
                                        )
                                    
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(contact.name)
                                            .font(.headline)
                                            .foregroundColor(.white)
                                        
                                        if let lastMsg = viewModel.lastMessages[contact.signingPublicKey] {
                                            Text(lastMsg.text)
                                                .font(.subheadline)
                                                .foregroundColor(.gray)
                                                .lineLimit(1)
                                        }
                                    }
                                    .padding(.leading, 8)
                                }
                                .padding(.vertical, 4)
                            }
                            .listRowBackground(Color(hue: 0.61, saturation: 0.2, brightness: 0.12))
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("TorX One")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    HStack {
                        Circle()
                            .fill(viewModel.isConnected ? Color.green : Color.red)
                            .frame(width: 10, height: 10)
                        Text(viewModel.isConnected ? "Connected" : "Connecting...")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack {
                        Button(action: { showingMyKey = true }) {
                            Image(systemName: "key.fill")
                                .foregroundColor(.white)
                        }
                        
                        Button(action: { showingAddContact = true }) {
                            Image(systemName: "plus")
                                .foregroundColor(.white)
                        }
                    }
                }
            }
            .sheet(isPresented: $showingAddContact) {
                AddContactSheet()
            }
            .sheet(isPresented: $showingMyKey) {
                MyKeySheet()
            }
        }
    }
}

struct MyKeySheet: View {
    @EnvironmentObject var viewModel: ChatViewModel
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if let identity = viewModel.identity {
                    let contactString = CryptoManager.shared.createContactString(identity: identity)
                    
                    Text("Your Contact Key")
                        .font(.headline)
                    
                    Text("Share this string with others so they can add you on TorX One. A future Tor-capable iOS build can include your .onion address here as well.")
                        .multilineTextAlignment(.center)
                        .foregroundColor(.gray)
                    
                    VStack {
                        Text(contactString)
                            .font(.system(.footnote, design: .monospaced))
                            .padding()
                            .background(Color.black.opacity(0.3))
                            .cornerRadius(8)
                            .textSelection(.enabled)
                        
                        Button(action: {
                            UIPasteboard.general.string = contactString
                        }) {
                            Label("Copy to Clipboard", systemImage: "doc.on.doc")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color(hue: 0.6, saturation: 0.8, brightness: 0.8))
                        .padding(.top, 8)
                    }
                    .padding()
                }
                
                Spacer()
            }
            .padding()
            .background(Color(hue: 0.61, saturation: 0.25, brightness: 0.08).ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}
