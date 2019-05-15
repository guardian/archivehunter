import React from "react";
import axios from "axios";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ReactTable, {ReactTableDefaults} from "react-table";
import {Link} from 'react-router-dom';
import ClickableIcon from "../common/ClickableIcon.jsx";

class EmailTemplateList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            templatesList: []
        };

        this.columns = [
            {
                Header: "Template name",
                accessor: "name",
                Cell: props=><Link to={"/admin/emailtemplates/"  + props.value }>{props.value}</Link>
            },
            {
                Header: "Timestamp",
                accessor: "timestamp"
            },
            {
                Header: "",
                accessor: "name",
                Cell: props=><ClickableIcon style={{width: "2em"}} onClick={evt=>this.requestDelete(props.value)} icon="trash-alt"/>
            }
        ];

        this.style = {
            backgroundColor: '#eee',
            border: '1px solid black',
            borderCollapse: 'collapse'
        };

        this.iconStyle = {
            color: '#aaa',
            paddingLeft: '5px',
            paddingRight: '5px'
        };

        this.requestDelete = this.requestDelete.bind(this);
    }

    requestDelete(templateName){
        this.setState({loading: true, lastError:null},()=>axios.delete("/api/emailtemplate/" + templateName).then(response=>{
            this.setState({loading: false, lastError:null, templatesList: this.state.templatesList.filter(tpl=>tpl.name!==templateName)});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    componentWillMount() {
        this.setState({loading: true}, ()=>axios.get("/api/emailtemplate").then(response=>{
            this.setState({
                loading: false,
                templatesList: response.data.entries
            })
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    render(){
        return <div>
            <span style={{display: this.state.lastError ? "block" : "none"}}>
                <ErrorViewComponent error={this.state.lastError}/>
            </span>
            <div style={{display: "block"}}><Link to="/admin/emailtemplates/new">New...</Link></div>
            <LoadingThrobber show={this.state.loading}/>
            <ReactTable
                data={this.state.templatesList}
                columns={this.columns}
                column={Object.assign({}, ReactTableDefaults.column, {headerClassName: 'dashboardheader'})}
                defaultSorted={[{
                    id: 'startedAt',
                    desc: true
                }]}
            />
        </div>
    }
}

export default EmailTemplateList;