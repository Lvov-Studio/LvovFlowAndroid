<?xml version="1.0" encoding="utf-8"?>
<?php
$ru_path = 'app/src/main/res/values-ru/strings.xml';
$en_path = 'app/src/main/res/values/strings.xml';

$ru_replacements = [
    "прокси-серверов" => "серверов",
    "прокси-сервер" => "сервер",
    "Прокси-сервер" => "Сервер",
    "турбо-режим HTTP" => "веб-ускорение",
    "прокси-приложений" => "ускоряемых приложений",
    "прямой прокси" => "прямое соединение",
    "Прямой прокси" => "Прямое соединение",
    "Прокси запустился." => "Ускорение запущено.",
    "прокси" => "сервер",
    "Прокси" => "Сервер",
    "Прокси:" => "Сервер:",
    "VPN-сервиса" => "сервиса ускорения",
    "Служба VPN" => "Служба ускорения",
    "туннель VPN" => "защищенный туннель",
    "VPN-провайдер" => "провайдер связи",
    "VPN-прокси" => "сервер",
    "маршрут VPN" => "маршрут ускорения",
    "VPN-соединения" => "ускорения",
    "VPN-соединение" => "ускорение",
    "соединение VPN" => "ускорение",
    "подключение VPN" => "активация ускорения",
    "VPN-приложения" => "приложения с ускорением",
    "VPN" => "Ускорение"
];

$en_replacements = [
    "Proxy server" => "Server",
    "proxy server" => "server",
    "Proxy" => "Server",
    "proxy" => "server",
    "Proxies" => "Servers",
    "proxies" => "servers",
    "VPN Service" => "Accelerator Service",
    "VPN connection" => "Connection",
    "VPN" => "Accelerator",
    "Proxy started." => "Accelerator started."
];

function process($path, $replacements) {
    if (!file_exists($path)) return "File $path not found\n";
    $content = file_get_contents($path);
    
    // Use preg_replace_callback to only replace text between > and <
    $new_content = preg_replace_callback('/>([^<]+)</u', function($matches) use ($replacements) {
        $text = $matches[1];
        foreach ($replacements as $search => $replace) {
            $text = str_replace($search, $replace, $text);
        }
        return ">$text<";
    }, $content);
    
    file_put_contents($path, $new_content);
    return "Processed $path\n";
}

echo process($ru_path, $ru_replacements);
echo process($en_path, $en_replacements);
echo "Done.\n";
