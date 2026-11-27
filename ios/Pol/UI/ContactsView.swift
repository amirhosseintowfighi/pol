import SwiftUI

struct ContactsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            List {
                let groups = Dictionary(grouping: model.contacts) { String($0.name.prefix(1)).uppercased() }
                ForEach(groups.keys.sorted(), id: \.self) { letter in
                    Section {
                        ForEach(groups[letter] ?? []) { c in
                            NavigationLink { ContactDetail(contact: c) } label: {
                                HStack(spacing: 12) {
                                    Avatar(name: c.name, size: 40)
                                    Text(verbatim: c.name).font(.body.weight(.semibold))
                                }
                            }
                        }
                    } header: {
                        Text(verbatim: letter).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    }
                }
            }
            .listStyle(.plain)
            .safeAreaInset(edge: .top, spacing: 0) { LinkBanner() }
            .overlay {
                if model.caps?.contacts != true && !model.demo {
                    ContentUnavailableView(L("contacts.off.title"), systemImage: "person.crop.circle.badge.xmark", description: Text(L("contacts.off.body")))
                }
            }
            .navigationTitle(L("tab.contacts"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { GatewayButton() } }
        }
    }
}

/// A contact card: large picture and name, glass Call and Message buttons, then each number.
struct ContactDetail: View {
    @Environment(AppModel.self) private var model
    let contact: Contact
    @State private var alert: String?

    var body: some View {
        let first = contact.phones.first?.number ?? ""
        List {
            Section {
                VStack(spacing: 10) {
                    Avatar(name: contact.name, size: 110)
                    Text(verbatim: contact.name).font(.largeTitle.weight(.semibold)).multilineTextAlignment(.center)
                    HStack(spacing: 12) {
                        ActionTile(symbol: "phone.fill", title: L("call"), enabled: Validate.dialNumber(first) != nil) {
                            alert = resultMessage(model.dial(first, subId: model.defaultVoiceSub))
                        }
                        NavigationLink { ThreadView(threadId: model.threadId(for: first), address: first) } label: {
                            ActionTileLabel(symbol: "message.fill", title: L("message"))
                        }
                        .buttonStyle(.plain)
                        .disabled(Validate.smsRecipient(first) == nil)
                    }
                    .padding(.top, 6)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                ForEach(contact.phones, id: \.number) { p in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: p.label ?? L("contacts.phone")).font(.subheadline)
                            Text(verbatim: ltr(p.number)).foregroundStyle(.blue).textSelection(.enabled)
                        }
                        Spacer()
                        Button { alert = resultMessage(model.dial(p.number, subId: model.defaultVoiceSub)) } label: {
                            Image(systemName: "phone.fill").foregroundStyle(.blue).frame(width: 36, height: 36).glassCircle()
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L("call.x %@", contact.name))
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
        .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
    }
}
