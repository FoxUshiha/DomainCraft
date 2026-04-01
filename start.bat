@echo off
title Compilador DomainCraft v1.0 - Java 17

echo ============================================
echo Compilador do Plugin DomainCraft
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\domain

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo spigot-api-1.20.1-R0.1-SNAPSHOT.jar esta na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
    set SPIGOT_PATH=spigot-api-1.20.1-R0.1-SNAPSHOT.jar
)

REM Verificar Vault API (opcional)
if not exist Vault.jar (
    echo [AVISO] Vault.jar nao encontrado na pasta raiz!
    echo O plugin nao requer Vault, mas pode ser usado para integracoes futuras.
    echo Continuando compilacao mesmo assim...
    echo.
    set VAULT_PATH=
) else (
    echo [OK] Vault API encontrado (opcional)
    set VAULT_PATH=Vault.jar
)

echo.
echo ============================================
echo Compilando DomainCraft...
echo ============================================
echo.

REM Montar classpath
set CLASSPATH="%SPIGOT_PATH%"
if defined VAULT_PATH (
    set CLASSPATH=%CLASSPATH%;"%VAULT_PATH%"
)

REM Mostrar classpath para debug
echo Classpath: %CLASSPATH%
echo.

REM Verificar se o arquivo fonte existe
if not exist src\com\foxsrv\domain\DomainCraft.java (
    echo ============================================
    echo ERRO: Arquivo fonte nao encontrado!
    echo ============================================
    echo.
    echo Caminho esperado: src\com\foxsrv\domain\DomainCraft.java
    echo.
    echo Estrutura de diretorios atual:
    echo.
    if exist src (
        echo Conteudo de src:
        dir /s /b src
    ) else (
        echo Pasta src nao encontrada!
    )
    echo.
    echo Criando estrutura de diretorios necessaria...
    mkdir src\com\foxsrv\domain 2>nul
    echo Por favor, coloque o arquivo DomainCraft.java em src\com\foxsrv\domain\
    pause
    exit /b 1
)

REM Criar arquivo com lista de fontes
dir /s /b src\com\foxsrv\domain\*.java > sources.txt

REM Compilar com as dependências necessárias
echo Compilando DomainCraft.java...
%JAVAC% --release 17 -d out ^
-cp %CLASSPATH% ^
-sourcepath src ^
-encoding UTF-8 ^
@sources.txt

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    echo.
    echo Possiveis causas:
    echo 1 - Erro de sintaxe no codigo
    echo 2 - Versao do Java incorreta
    echo 3 - Spigot API nao encontrada ou incompativel
    del sources.txt
    pause
    exit /b 1
)

del sources.txt

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Criando plugin.yml manualmente...
echo ============================================
echo.

REM Criar plugin.yml diretamente na pasta out
(
    echo name: DomainCraft
    echo version: 1.0
    echo main: com.foxsrv.domain.DomainCraft
    echo api-version: 1.20
    echo depend: []
    echo softdepend: [Vault]
    echo author: FoxSRV
    echo description: Domain system for custom craft in your server
    echo.
    echo commands:
    echo   domain:
    echo     description: Main DomainCraft command
    echo     usage: /domain ^<create^|set^|edit^|delete^|list^>
    echo     aliases: [d]
    echo     permission: domaincraft.use
    echo.
    echo permissions:
    echo   domaincraft.use:
    echo     description: Allows using domains
    echo     default: true
    echo   domaincraft.admin:
    echo     description: Allows admin commands
    echo     default: op
) > out\plugin.yml

echo [OK] plugin.yml criado

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
echo Criando DomainCraft.jar...
%JAR% cf DomainCraft.jar com plugin.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\DomainCraft.jar
echo.
dir out\DomainCraft.jar
echo.
echo ============================================
echo RESUMO DA COMPILACAO:
echo ============================================
echo.
echo - Data/Hora: %date% %time%
echo - Java Version: 17
echo - Spigot API: OK
if defined VAULT_PATH (
    echo - Vault API: OK (opcional)
) else (
    echo - Vault API: NAO ENCONTRADO (opcional)
)
echo - Arquivo fonte: src\com\foxsrv\domain\DomainCraft.java
echo.
echo ============================================
echo ARQUIVOS COMPILADOS:
echo ============================================
echo.
dir /b src\com\foxsrv\domain\*.java
echo.
echo ============================================
echo REQUISITOS PARA EXECUCAO:
echo ============================================
echo.
echo 1 - Spigot/Paper 1.20+ necessario
echo 2 - Java 17 ou superior
echo 3 - Nenhuma dependencia obrigatoria (Vault opcional)
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie out\DomainCraft.jar para a pasta plugins do servidor
echo 2 - Reinicie o servidor ou use /reload confirm
echo 3 - Os dados serao salvos em plugins/DomainCraft/data.dat
echo.
echo ============================================
echo COMANDOS DO PLUGIN:
echo ============================================
echo.
echo JOGADORES:
echo Clique com botao direito em um bloco de dominio configurado
echo.
echo ADMIN:
echo /domain create ^<nome^> - Cria um novo dominio no bloco que esta olhando
echo /domain set ^<nome^> ^<permissao^> - Define a permissao necessaria para acessar o dominio
echo /domain edit ^<nome^> - Abre o menu de edicao do dominio
echo /domain delete ^<nome^> - Deleta um dominio
echo /domain list - Lista todos os dominios
echo.
echo ============================================
echo PERMISSOES:
echo ============================================
echo.
echo domaincraft.use - Pode usar dominios (default: true)
echo domaincraft.admin - Pode usar comandos admin (default: op)
echo.
echo ============================================
echo FUNCIONALIDADES:
echo ============================================
echo.
echo - Sistema de dominios com blocos personalizados
echo - Permissoes por dominio
echo - Criacao de recipes customizadas via menu
echo - Sistema de craft com verificacao de itens
echo - Suporte a qualquer tipo de item
echo - Serializacao completa de itens (preserva NBT)
echo - Suporte a custom model data
echo - Menus intuitivos com navegacao
echo - Suporte a drag and drop no editor
echo - Validacao de permissao ao clicar no bloco
echo - Auto-salvamento em arquivo data.dat
echo - Sistema de edicao de recipes existentes
echo - Delecao de recipes com Shift+Click
echo.
echo ============================================
echo TUTORIAL RAPIDO:
echo ============================================
echo.
echo 1. Crie um dominio: /domain create minhaoficina
echo 2. Defina permissao: /domain set minhaoficina minhaoficina.usar
echo 3. Edite o dominio: /domain edit minhaoficina
echo 4. Adicione crafts no menu de edicao
echo 5. Jogadores com permissao clicam no bloco para craftar
echo.
echo ============================================
echo ESTRUTURA DO EDITOR DE CRAFTS:
echo ============================================
echo.
echo - Slot central superior: Item de exibicao (icone do craft)
echo - Lado esquerdo (slots 10-16): Ingredientes necessarios
echo - Lado direito (slots 28-34): Resultados do craft
echo - Botao SAVE: Salva a recipe
echo.
echo ============================================
echo.

pause
