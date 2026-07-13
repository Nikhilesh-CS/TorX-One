import SwiftUI

/// Identity setup and unlock view — shown when no identity is active.
struct SetupView: View {
    @EnvironmentObject var viewModel: ChatViewModel
    
    @State private var name: String = ""
    @State private var passphrase: String = ""
    @State private var isCreating: Bool = true // true = create new, false = unlock existing
    
    var body: some View {
        ZStack {
            // Deep dark background
            Color(hue: 0.61, saturation: 0.25, brightness: 0.08)
                .ignoresSafeArea()
            
            VStack(spacing: 32) {
                Spacer()
                
                // Logo / Title
                VStack(spacing: 12) {
                    Image(systemName: "shield.checkered")
                        .font(.system(size: 64))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [Color(hue: 0.55, saturation: 0.8, brightness: 0.9),
                                         Color(hue: 0.75, saturation: 0.7, brightness: 0.95)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    
                    Text("TorX One")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    
                    Text("End-to-End Encrypted Messaging")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }
                
                // Mode Switcher
                if viewModel.hasIdentity {
                    Picker("Mode", selection: $isCreating) {
                        Text("Unlock").tag(false)
                        Text("New Identity").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 40)
                }
                
                // Input Fields
                VStack(spacing: 16) {
                    if isCreating {
                        TextField("", text: $name, prompt: Text("Display Name").foregroundColor(.gray))
                            .textFieldStyle(.plain)
                            .padding(14)
                            .background(Color.white.opacity(0.08))
                            .cornerRadius(12)
                            .foregroundColor(.white)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.words)
                    }
                    
                    SecureField("", text: $passphrase, prompt: Text("Passphrase").foregroundColor(.gray))
                        .textFieldStyle(.plain)
                        .padding(14)
                        .background(Color.white.opacity(0.08))
                        .cornerRadius(12)
                        .foregroundColor(.white)
                }
                .padding(.horizontal, 32)
                
                // Error Message
                if let error = viewModel.error {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(Color(hue: 0.0, saturation: 0.7, brightness: 0.9))
                        .padding(.horizontal, 32)
                        .multilineTextAlignment(.center)
                }
                
                // Action Button
                Button(action: performAction) {
                    HStack {
                        Image(systemName: isCreating ? "key.fill" : "lock.open.fill")
                        Text(isCreating ? "Create Identity" : "Unlock")
                    }
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(16)
                    .background(
                        LinearGradient(
                            colors: [Color(hue: 0.55, saturation: 0.7, brightness: 0.7),
                                     Color(hue: 0.7, saturation: 0.6, brightness: 0.6)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(14)
                }
                .padding(.horizontal, 32)
                .disabled(isCreating ? name.isEmpty || passphrase.isEmpty : passphrase.isEmpty)
                .opacity(isCreating ? (name.isEmpty || passphrase.isEmpty ? 0.5 : 1.0) : (passphrase.isEmpty ? 0.5 : 1.0))
                
                Spacer()
                Spacer()
            }
        }
        .onAppear {
            // If identity already exists, default to unlock mode
            if viewModel.hasIdentity {
                isCreating = false
            }
        }
    }
    
    private func performAction() {
        if isCreating {
            viewModel.createIdentity(name: name, passphrase: passphrase)
        } else {
            viewModel.unlock(passphrase: passphrase)
        }
    }
}
