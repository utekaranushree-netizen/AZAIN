# Generates er_diagram.dot (editable source) from a description of the schema.
import os
# Run:  python make_er.py && dot -Tsvg er_diagram.dot -o er_diagram.svg
E = {  # entity: [(attribute, flag)]  flag: PK / FK / UK / ''
 'CATEGORY': [('category_id','PK'),('category_name','UK'),('description','')],
 'SUPPLIER': [('supplier_id','PK'),('supplier_name',''),('contact_person',''),('phone',''),('email','UK'),('city','')],
 'PRODUCT': [('product_id','PK'),('product_name',''),('brand',''),('category_id','FK'),('supplier_id','FK'),('cost_price',''),('price',''),('stock_qty',''),('reorder_level',''),('is_active',''),('created_at','')],
 'CUSTOMER': [('customer_id','PK'),('customer_name',''),('phone','UK'),('email','UK'),('city',''),('loyalty_points',''),('joined_on','')],
 'EMPLOYEE': [('emp_id','PK'),('emp_name',''),('role',''),('phone',''),('salary',''),('hired_on','')],
 'ORDER': [('order_id','PK'),('customer_id','FK'),('emp_id','FK'),('order_date',''),('discount_percent',''),('status',''),('request_key','UK')],
 'PAYMENT': [('payment_id','PK'),('order_id','FKUK'),('amount',''),('method',''),('paid_at','')],
 'PURCHASE_ORDER': [('po_id','PK'),('supplier_id','FK'),('po_date',''),('status','')],
 'STOCK_LOG': [('log_id','PK'),('product_id','FK'),('change_qty',''),('reason',''),('logged_at','')],
}
# relationship: (name, left, right, left_card, right_card, left_total, right_total, attributes)
R = [
 ('Classified under','CATEGORY','PRODUCT','1','N',False,True,[]),
 ('Supplies','SUPPLIER','PRODUCT','1','N',False,False,[]),
 ('Places','CUSTOMER','ORDER','1','N',False,False,[]),
 ('Handles','EMPLOYEE','ORDER','1','N',False,True,[]),
 ('Contains','ORDER','PRODUCT','M','N',True,False,['item_id (PK)','quantity','unit_price','line_total']),
 ('Paid by','ORDER','PAYMENT','1','1',False,True,[]),
 ('Raises','SUPPLIER','PURCHASE_ORDER','1','N',False,True,[]),
 ('Includes','PURCHASE_ORDER','PRODUCT','M','N',False,False,['po_item_id (PK)','quantity','unit_cost']),
 ('Logged in','PRODUCT','STOCK_LOG','1','N',False,True,[]),
]
NAVY,IND='#1b2559','#3d4fd1'
o=['graph ER {','graph [rankdir=LR, splines=true, nodesep=0.45, ranksep=1.0, pad=0.4, fontname="Helvetica", label=<<b>Stationary Shop - Entity-Relationship Diagram</b>>, labelloc=t, fontsize=26];',
   'node [fontname="Helvetica", fontsize=11]; edge [fontname="Helvetica-Bold", fontsize=13, color="%s", fontcolor="%s"];'%(NAVY,IND)]
for n,attrs in E.items():
    rows=''
    for a,f in attrs:
        t=a
        if f=='PK': t='<u><b>%s</b></u>'%a
        if f=='FK': t='<i>%s</i> (FK)'%a
        if f=='FKUK': t='<i>%s</i> (FK, UK)'%a
        if f=='UK': t='%s (UK)'%a
        rows+='<tr><td align="left">%s</td></tr>'%t
    o.append('%s [shape=plain, label=<<table border="0" cellborder="1" cellspacing="0" cellpadding="5" bgcolor="white" color="%s"><tr><td bgcolor="%s"><font color="white"><b>%s</b></font></td></tr>%s</table>>];'%(n,NAVY,NAVY,n.replace('_',' '),rows))
for i,(nm,l,r,lc,rc,lt,rt,at) in enumerate(R):
    rid='R%d'%i
    o.append('%s [shape=diamond, style=filled, fillcolor="#fde9a8", color="%s", label="%s", width=1.5, height=0.9];'%(rid,NAVY,nm))
    dbl='"%s:%s"'%(NAVY,NAVY)
    o.append('%s -- %s [label="%s", color=%s];'%(l,rid,lc,dbl if lt else '"%s"'%NAVY))
    o.append('%s -- %s [label="%s", color=%s];'%(rid,r,rc,dbl if rt else '"%s"'%NAVY))
    for j,a in enumerate(at):
        aid='A%d_%d'%(i,j)
        u='<u>%s</u>'%a.replace(' (PK)','') if '(PK)' in a else a
        o.append('%s [shape=ellipse, style=filled, fillcolor="#e9ecff", color="%s", label=<%s>, fontsize=10];'%(aid,IND,u))
        o.append('%s -- %s [color="%s", style=dashed];'%(rid,aid,IND))
o.append('legend [shape=plain, label=<<table border="0" cellborder="1" cellspacing="0" cellpadding="4"><tr><td align="left"><b>Legend</b></td></tr><tr><td align="left">Rectangle = entity; diamond = relationship; ellipse = relationship attribute</td></tr><tr><td align="left"><u><b>underlined</b></u> = primary key; <i>italic (FK)</i> = foreign key; (UK) = unique key</td></tr><tr><td align="left">Double line = total participation; single line = partial participation</td></tr><tr><td align="left">1 / N / M = cardinality (one / many)</td></tr></table>>];')
o.append('}')
if __name__ == '__main__':      # importing this file (the schema checker does) must not write anything
    open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'er_diagram.dot'), 'w').write('\n'.join(o))
