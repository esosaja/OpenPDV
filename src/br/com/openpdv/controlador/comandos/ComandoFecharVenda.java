package br.com.openpdv.controlador.comandos;

import br.com.openpdv.controlador.core.CoreService;
import br.com.openpdv.controlador.core.Util;
import br.com.openpdv.modelo.core.EComandoSQL;
import br.com.openpdv.modelo.core.OpenPdvException;
import br.com.openpdv.modelo.core.Sql;
import br.com.openpdv.modelo.core.filtro.ECompara;
import br.com.openpdv.modelo.core.filtro.FiltroNumero;
import br.com.openpdv.modelo.core.parametro.*;
import br.com.openpdv.modelo.ecf.EcfPagamento;
import br.com.openpdv.modelo.ecf.EcfVenda;
import br.com.openpdv.modelo.ecf.EcfVendaProduto;
import br.com.openpdv.visao.core.Aguarde;
import br.com.openpdv.visao.core.Caixa;
import br.com.phdss.ECF;
import br.com.phdss.EComandoECF;
import br.com.phdss.TEF;
import br.com.phdss.controlador.PAF;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.JOptionPane;
import org.apache.log4j.Logger;

/**
 * Classe que realiza a acao de fechar uma venda.
 *
 * @author Pedro H. Lira
 */
public class ComandoFecharVenda implements IComando {

    private Logger log;
    private List<EcfPagamento> pagamentos;
    private double bruto;
    private double acres_desc;
    private double troco;
    private EcfVenda venda;

    /**
     * Construtor padrao.
     *
     * @param pagamentos a lista de pagamentos realizados.
     * @param bruto o valor total da venda.
     * @param acres_desc valor de acrescimo (positivo) ou desconto (negativo).
     * @param troco o valor do troco da venda.
     */
    public ComandoFecharVenda(List<EcfPagamento> pagamentos, double bruto, double acres_desc, double troco) {
        this.log = Logger.getLogger(ComandoFecharVenda.class);
        this.pagamentos = pagamentos;
        this.bruto = bruto;
        this.acres_desc = acres_desc;
        this.troco = troco;
        this.venda = Caixa.getInstancia().getVenda();
    }

    @Override
    public void executar() throws OpenPdvException {
        try {
            TEF.bloquear(true);
            // fecha a venda no cupom
            fecharVendaECF();
            // salva no bd
            fecharVendaBanco();
            // salva o documento para relatorio
            new ComandoSalvarDocumento("RV").executar();
            // imprime os cartoes se tiver
            new ComandoImprimirCartao(pagamentos, troco).executar();
            // coloca na tela
            fecharVendaTela();
            TEF.bloquear(false);
        } catch (OpenPdvException ex) {
            TEF.bloquear(false);
            throw ex;
        } finally {
            Aguarde.getInstancia().setVisible(false);
        }
    }

    @Override
    public void desfazer() throws OpenPdvException {
        // comando nao aplicavel.
    }

    /**
     * Metodo para fechar uma venda no ECF.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaECF() throws OpenPdvException {
        try {
            // sub totaliza
            String AD = Util.formataNumero(acres_desc, 1, 2, false).replace(",", ".");
            StringBuilder sb = new StringBuilder();
            sb.append(Util.formataTexto("MD5: " + PAF.AUXILIAR.getProperty("out.autenticado"), " ", ECF.COL, true));

            // identifica o operador do caixa e o vendedor
            String operador = "OPERADOR: " + venda.getSisUsuario().getSisUsuarioLogin();
            if (venda.getSisVendedor() != null) {
                operador += " - VENDEDOR: " + venda.getSisVendedor().getSisUsuarioLogin();
            }
            sb.append(Util.formataTexto(operador, " ", ECF.COL, true));

            // caso nao tenha sido informado o cliente
            if (venda.getSisCliente() == null) {
                sb.append(Util.formataTexto("CONSUMIDOR NAO INFORMOU O CPF/CNPJ", " ", ECF.COL, true));
            } else if (Caixa.getInstancia().getVenda().isInformouCliente() == false) {
                sb.append("CNPJ/CPF: ").append(venda.getSisCliente().getSisClienteDoc()).append(ECF.SL);
                if (!venda.getSisCliente().getSisClienteNome().equals("")) {
                    sb.append("NOME:     ").append(venda.getSisCliente().getSisClienteNome()).append(ECF.SL);
                }
                if (!venda.getSisCliente().getSisClienteEndereco().equals("")) {
                    sb.append("ENDEREÇO: ").append(venda.getSisCliente().getSisClienteEndereco()).append(ECF.SL);
                }
            }

            // caso seja no estado de MG, colocar o minas legal
            if (PAF.AUXILIAR.getProperty("paf.minas_legal").equalsIgnoreCase("SIM")) {
                sb.append("MINAS LEGAL: ");
                sb.append(PAF.AUXILIAR.getProperty("cli.cnpj")).append(" ");
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyyyy")).append(" ");
                sb.append(Util.formataNumero(bruto + acres_desc, 0, 2, true).replace(",", ""));
            } else if (PAF.AUXILIAR.getProperty("paf.cupom_mania").equalsIgnoreCase("SIM")) {
                // caso seja no estado de RJ, colocar o cupom mania
                sb.append(Util.formataTexto("CUPOM MANIA - CONCORRA A PREMIOS", " ", ECF.COL, true));
                sb.append("ENVIE SMS P/ 6789: ");
                sb.append(Util.formataNumero(PAF.AUXILIAR.getProperty("cli.ie"), 8, 0, false));
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyyyy"));
                sb.append(Util.formataNumero(venda.getEcfVendaCoo(), 6, 0, false));
                sb.append(Util.formataNumero(Caixa.getInstancia().getImpressora().getEcfImpressoraCaixa(), 3, 0, false));
            }

            String[] resp = ECF.enviar(EComandoECF.ECF_SubtotalizaCupom, AD, sb.toString());
            if (ECF.ERRO.equals(resp[0])) {
                log.error("Erro ao fechar a venda. -> " + resp[1]);
                throw new OpenPdvException(resp[1]);
            }
            // soma os pagamento que possuem o mesmo codigo
            Map<String, Double> pags = new HashMap<>();
            for (EcfPagamento pag : pagamentos) {
                if (pags.containsKey(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo())) {
                    double valor = pag.getEcfPagamentoValor() + pags.get(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo());
                    pags.put(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo(), valor);
                } else {
                    pags.put(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo(), pag.getEcfPagamentoValor());
                }
            }
            for (Entry<String, Double> pag : pags.entrySet()) {
                String valor = Util.formataNumero(pag.getValue(), 1, 2, false).replace(",", ".");
                ECF.enviar(EComandoECF.ECF_EfetuaPagamento, pag.getKey(), valor);
            }
            // fecha a venda
            resp = ECF.enviar(EComandoECF.ECF_FechaCupom);
            if (ECF.ERRO.equals(resp[0])) {
                log.error("Erro ao fechar a venda. -> " + resp[1]);
                throw new OpenPdvException(resp[1]);
            } else {
                // atualiza o gt
                try {
                    resp = ECF.enviar(EComandoECF.ECF_GrandeTotal);
                    if (ECF.OK.equals(resp[0])) {
                        PAF.AUXILIAR.setProperty("ecf.gt", resp[1]);
                        PAF.criptografar();
                    } else {
                        throw new Exception(resp[1]);
                    }
                } catch (Exception ex) {
                    log.error("Erro ao atualizar o GT. -> ", ex);
                    throw new OpenPdvException("Erro ao atualizar o GT.");
                }
            }
        } catch (Exception ex) {
            TEF.bloquear(false);
            int escolha = JOptionPane.showOptionDialog(null, "Impressora não responde, tentar novamente?", "TEF",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[]{"SIM", "NÃO"}, JOptionPane.YES_OPTION);
            TEF.bloquear(true);
            if (escolha == JOptionPane.YES_OPTION) {
                fecharVendaECF();
            } else {
                throw new OpenPdvException(ex);
            }
        }
    }

    /**
     * Metodo para fechar uma venda no BD.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaBanco() throws OpenPdvException {
        // fecha a venda
        List<Sql> sqls = new ArrayList<>();
        FiltroNumero fn = new FiltroNumero("ecfVendaId", ECompara.IGUAL, venda.getId());
        ParametroNumero pn1 = new ParametroNumero("ecfVendaBruto", bruto);
        ParametroNumero pn2 = new ParametroNumero(acres_desc > 0 ? "ecfVendaAcrescimo" : "ecfVendaDesconto", Math.abs(acres_desc));
        ParametroNumero pn3 = new ParametroNumero("ecfVendaLiquido", bruto + acres_desc);
        ParametroBinario pb = new ParametroBinario("ecfVendaFechada", true);
        ParametroObjeto po1 = new ParametroObjeto("sisCliente", venda.getSisCliente());
        ParametroObjeto po2 = new ParametroObjeto("sisVendedor", venda.getSisVendedor());
        GrupoParametro gp = new GrupoParametro(new IParametro[]{pn1, pn2, pn3, pb, po1, po2});
        Sql sql = new Sql(new EcfVenda(), EComandoSQL.ATUALIZAR, fn, gp);
        sqls.add(sql);

        // atualiza estoque e produtos
        double rateado = acres_desc / venda.getEcfVendaProdutos().size();
        for (EcfVendaProduto vp : venda.getEcfVendaProdutos()) {
            if (rateado != 0) {
                FiltroNumero vp_fn = new FiltroNumero("ecfVendaProdutoId", ECompara.IGUAL, vp.getId());
                ParametroNumero vp_pn1 = new ParametroNumero(rateado > 0 ? "ecfVendaProdutoAcrescimo" : "ecfVendaProdutoDesconto", Math.abs(rateado));
                ParametroNumero vp_pn2 = new ParametroNumero("ecfVendaProdutoLiquido", vp.getEcfVendaProdutoBruto() + rateado);
                ParametroNumero vp_pn3 = new ParametroNumero("ecfVendaProdutoTotal", (vp.getEcfVendaProdutoBruto() + rateado) * vp.getEcfVendaProdutoQuantidade());
                GrupoParametro vp_gp = new GrupoParametro(new IParametro[]{vp_pn1, vp_pn2, vp_pn3});
                Sql sql1 = new Sql(new EcfVendaProduto(), EComandoSQL.ATUALIZAR, vp_fn, vp_gp);
                sqls.add(sql1);
            }

            if (!vp.getEcfVendaProdutoCancelado()) {
                // fatorando a quantida no estoque
                double qtd = vp.getEcfVendaProdutoQuantidade();
                if (vp.getProdEmbalagem().getProdEmbalagemId() != vp.getProdProduto().getProdEmbalagem().getProdEmbalagemId()) {
                    qtd *= vp.getProdEmbalagem().getProdEmbalagemUnidade();
                    qtd /= vp.getProdProduto().getProdEmbalagem().getProdEmbalagemUnidade();
                }
                // atualiza o estoque
                ParametroFormula pf = new ParametroFormula("prodProdutoEstoque", -1 * qtd);
                FiltroNumero fn1 = new FiltroNumero("prodProdutoId", ECompara.IGUAL, vp.getProdProduto().getId());
                Sql sql2 = new Sql(vp.getProdProduto(), EComandoSQL.ATUALIZAR, fn1, pf);
                sqls.add(sql2);
            }
        }
        CoreService service = new CoreService();
        service.executar(sqls);
    }

    /**
     * Metodo para fechar uma venda na Tela.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaTela() throws OpenPdvException {
        Caixa.getInstancia().getBobina().removeAllElements();
        Caixa.getInstancia().modoDisponivel();
    }
}
