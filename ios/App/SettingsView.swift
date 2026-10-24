import SwiftUI

struct SettingsView: View {
    @Environment(House.self) private var house
    @Environment(\.dismiss) private var dismiss
    @State private var server = Settings.server

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Server", text: $server)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("HomeControl server")
                } footer: {
                    Text("Default: \(Settings.defaultServer). The server runs everything — schedules, fades, the hubs — so this is all the app needs.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        Settings.server = server.trimmingCharacters(in: .whitespaces)
                        dismiss()
                        Task { await house.refresh(force: true) }
                    }
                }
            }
        }
    }
}
