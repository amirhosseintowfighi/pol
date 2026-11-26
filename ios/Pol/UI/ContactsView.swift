import SwiftUI

struct ContactsView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""

    private var filtered: [Contact] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return model.contacts }
        let digits = q.filter(\.isNumber)
        return model.contacts.filter { c in c.name.localizedCaseInsensitiveContains(q) || (!digits.isEmpty && c.phones.contains { $0.number.contains(digits) }) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                LinkBanner()
                List {
                    let groups = Dictionary(grouping: filtered) { String($0.name.prefix(1)).uppercased() }
                    ForEach(groups.keys.sorted(), id: \.self) { letter in
                        Section(letter) {
                            ForEach(groups[letter] ?? []) { c in
                                NavigationLink { ContactDetail(contact: c) } label: {
                                    HStack(spacing: 12) { Avatar(name: c.name, size: 36); Text(verbatim: c.name) }
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .overlay {
                    if model.caps?.contacts != true && !model.demo {
                        ContentUnavailableView(L("contacts.off.title"), systemImage: "person.crop.circle.badge.xmark", description: Text(L("contacts.off.body")))
                    } else if filtered.isEmpty {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .navigationTitle(L("tab.contacts"))
            .searchable(text: $query, prompt: L("search.contacts"))
        }
    }
}

private struct ContactDetail: View {
    @Environment(AppModel.self) private var model
    let contact: Contact
    @State private var alert: String?

    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Avatar(name: contact.name, size: 88)
                    Text(verbatim: contact.name).font(.title.weight(.semibold))
                }.frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }
            ForEach(contact.phones, id: \.number) { p in
                Section(p.label ?? L("contacts.phone")) {
                    HStack {
                        Text(verbatim: ltr(p.number)).textSelection(.enabled)
                        Spacer()
                        Button { alert = resultMessage(model.dial(p.number, subId: model.defaultVoiceSub)) } label: { Image(systemName: "phone.fill") }
                            .buttonStyle(.borderless).accessibilityLabel(L("call.x %@", contact.name))
                        NavigationLink { ThreadView(threadId: model.threadId(for: p.number), address: p.number) } label: { Image(systemName: "message.fill") }
                            .frame(width: 44).accessibilityLabel(L("message.x %@", contact.name))
                    }
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .alert(alert ?? "", isPresented: .init(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button(L("ok")) {} }
    }
}
