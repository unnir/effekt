'use strict';

import { ExtensionContext, workspace, commands, TerminalOptions, window } from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions } from 'vscode-languageclient';
import { Monto } from './monto';

let client: LanguageClient;

export function activate(context: ExtensionContext) {

    let config = workspace.getConfiguration("effekt");

    let compilerPath = config.get<string>("compiler") || ""

    let java = "java";
    let args: string[] = [
        "-jar",
        compilerPath,
        "--server"
    ];

    let serverOptions: ServerOptions = {
        run: {
            command: java,
            args: args,
            options: {}
        },
        debug: {
            command: java,
            args: args.concat(["--debug"]),
            options: {}
        }
    };

    let clientOptions: LanguageClientOptions = {
        documentSelector: [
            {
                scheme: 'file',
                language: 'effekt'
            }
        ],
        diagnosticCollectionName: "effekt"
    };

    client = new LanguageClient(
        'effektLanguageServer',
        'Effekt Language Server',
        serverOptions,
        clientOptions
    );

    Monto.setup("effekt", context, client);

    context.subscriptions.push(client.start());

    window.onDidOpenTerminal(terminal => {
		console.log("Terminal opened. Total count: " + (<any>window).terminals.length);
	});

    // create an Effekt-specific shell
    context.subscriptions.push(commands.registerCommand('effekt.Terminal', () => {
        let startScript = config.get<string>("startscript") || ""
        let pathToShell = config.get<string>("shell") || ""
        
        let workspacePath = "${workspaceFolder}";
		let pathVar = "${env:Path}";
        let options: TerminalOptions;
        
        if(pathToShell==""){
            window.showInformationMessage('Path to shell binary not set! Using system default...');
        }

        if(startScript==""){
            window.showInformationMessage('Path to "effekt"-script not set!');
        }else{
            pathVar = pathVar + ";" + startScript;
        }
        options = {env: { ["Path"] : pathVar}, name: "effekt Shell", cwd: workspacePath || "${execPath}", shellPath: pathToShell || "" };

        let effektTerminal = window.createTerminal(options);
        if (effektTerminal) {
            effektTerminal.show();
            window.showInformationMessage('Effekt-Terminal created.');   
        }
        else {
            window.showInformationMessage('Failed to create Effekt-Terminal.');
        }
		
	}));
}

export function deactivate(): Thenable<void> | undefined {
	if (!client) {
		return undefined;
	}
	return client.stop();
}
