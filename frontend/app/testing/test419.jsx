import React from 'react';
import axios from 'axios';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";

class Test419Component extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            loadedMessage: null,
            lastError: null
        }
    }

    componentWillMount(){
        this.setState({loading: true, lastError:null}, ()=>axios.get("/test/api419").then(response=>{
            this.setState({loading: false, loadedMessage: response.data});
        }).catch(err=>{
            this.setState({loading: false, lastError: err});
        }))
    }
    
    render() {
        return <table>
            <tr><td>Loading</td><td>{this.state.loading}</td></tr>
            <tr><td>Loaded message</td><td>{this.state.loadedMessage ? this.state.loadedMessage : "null"}</td></tr>
            <tr><td>Error</td><td><ErrorViewComponent error={this.state.lastError}/></td></tr>
        </table>
    }
}

export default Test419Component;